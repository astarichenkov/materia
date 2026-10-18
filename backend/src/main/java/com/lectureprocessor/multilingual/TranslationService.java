package com.lectureprocessor.multilingual;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.llm.CloudCostService;
import com.lectureprocessor.llm.LlmProvider;
import com.lectureprocessor.llm.LlmResult;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.pipeline.Fingerprints;
import com.lectureprocessor.process.CancellationToken;
import com.lectureprocessor.text.PromptTemplates;
import com.lectureprocessor.util.FileChecksum;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Segment-based, resumable translation of a lecture transcript.
 *
 * <p>Guarantees:</p>
 * <ul>
 *   <li>the source transcript is never modified;</li>
 *   <li>segments are translated in bounded batches with strict ID validation;</li>
 *   <li>every successful batch is persisted immediately, so a provider outage
 *       resumes only the missing segments;</li>
 *   <li>the model/prompt/glossary/settings combination is part of the checkpoint
 *       identity, so old translations are never mixed with a new transcript.</li>
 * </ul>
 */
@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final int MAX_GLOSSARY_PROMPT_CHARS = 4000;

    private final TranslationRepository repository;
    private final TranslationGlossaryService glossary;
    private final PromptTemplates prompts;
    private final CloudCostService costService;
    private final MultilingualProperties properties;
    private final ObjectMapper objectMapper;

    public TranslationService(TranslationRepository repository, TranslationGlossaryService glossary,
            PromptTemplates prompts, CloudCostService costService,
            MultilingualProperties properties, ObjectMapper objectMapper) {
        this.repository = repository;
        this.glossary = glossary;
        this.prompts = prompts;
        this.costService = costService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Latest stored translation plus its persisted segments. */
    public Optional<StoredTranslation> latest(String lectureId) {
        return repository.findLatest(lectureId)
                .map(translation -> new StoredTranslation(translation,
                        repository.segments(translation.id())));
    }

    /** Segments successfully translated in the latest run. */
    public List<TranslationSegment> latestSegments(String lectureId) {
        return latest(lectureId).map(StoredTranslation::segments).orElse(List.of()).stream()
                .filter(TranslationSegment::done)
                .toList();
    }

    public TranslationOutcome translate(TranslationRequest request) {
        LlmRunConfig config = request.resolved().config();
        String glossaryVersion = glossary.version(request.sourceLanguage(), request.targetLanguage());
        String settingsHash = settingsHash(config, request, glossaryVersion);
        LectureTranslation translation = repository.createOrGet(request.lectureId(),
                request.sourceFingerprint(), request.sourceLanguage(), request.targetLanguage(),
                request.resolved().providerId(), config.model(), properties.promptVersion(),
                glossaryVersion, settingsHash, request.sourceSegments().size());

        Map<Integer, TranslationSegment> existing = new HashMap<>();
        for (TranslationSegment segment : repository.segments(translation.id())) {
            existing.put(segment.sequence(), segment);
        }
        List<TranslationSegment> working = new ArrayList<>();
        for (TranscriptSegment source : request.sourceSegments()) {
            TranslationSegment stored = existing.get(source.sequence());
            if (stored != null && source.text().equals(stored.sourceText()) && stored.done()) {
                working.add(stored);
            } else {
                int attempts = stored == null ? 0 : stored.attempts();
                working.add(new TranslationSegment(source.sequence(), source.startMs(), source.endMs(),
                        source.text(), null, SegmentTranslationStatus.PENDING, "OK", null, null,
                        attempts));
            }
        }
        int done = (int) working.stream().filter(TranslationSegment::done).count();
        if (done == working.size() && !working.isEmpty()) {
            log.info("Translation {} for lecture {} already complete ({}/{} segments)",
                    translation.id(), request.lectureId(), done, working.size());
            return resumed(translation, working, config);
        }
        // Persist the pending rows first so an interrupted run is fully resumable.
        repository.upsertSegments(translation.id(), working);

        String systemPrompt = prompts.get(properties.promptTemplate())
                .replace("{{GLOSSARY}}", glossary.promptBlock(request.sourceLanguage(),
                        request.targetLanguage(), MAX_GLOSSARY_PROMPT_CHARS));
        List<TranslationSegment> pending = working.stream().filter(s -> !s.done()).toList();
        List<List<TranslationSegment>> batches = batch(pending);
        Budget budget = new Budget(properties.maxLlmCalls(), properties.tokenBudget());
        int[] callCounter = {0};
        int[] inputTokens = {0};
        int[] outputTokens = {0};
        List<String> warnings = new ArrayList<>();

        for (List<TranslationSegment> batch : batches) {
            request.cancellation().throwIfCancelled();
            if (!budget.canCall()) {
                warnings.add("Достигнут лимит LLM-вызовов/токенов; часть сегментов не переведена");
                break;
            }
            processBatch(batch, 0, request, systemPrompt, working, budget, callCounter,
                    inputTokens, outputTokens, warnings);
            repository.upsertSegments(translation.id(), working);
            writeProgress(translation, working, warnings, callCounter[0], inputTokens[0],
                    outputTokens[0], config, request.resolved().cloud(), TranslationStatus.RUNNING);
        }
        request.cancellation().throwIfCancelled();

        applyQualityChecks(working, request.sourceLanguage(), request.targetLanguage());
        retrySuspiciousSegments(working, request, systemPrompt, budget, callCounter, inputTokens,
                outputTokens, warnings);
        applyQualityChecks(working, request.sourceLanguage(), request.targetLanguage());
        repository.upsertSegments(translation.id(), working);
        int translated = (int) working.stream().filter(TranslationSegment::done).count();
        int failed = working.size() - translated;
        TranslationStatus status = failed == 0 ? TranslationStatus.COMPLETED
                : translated > 0 ? TranslationStatus.PARTIAL : TranslationStatus.FAILED;
        if (status == TranslationStatus.PARTIAL) {
            warnings.add("Переведено " + translated + " из " + working.size()
                    + " сегментов; оставшиеся можно повторить (resume)");
        }
        LectureTranslation saved = writeProgress(translation, working, warnings, callCounter[0],
                inputTokens[0], outputTokens[0], config, request.resolved().cloud(), status);
        return new TranslationOutcome(saved, working, status, working.size(), translated, failed,
                warnings, inputTokens[0], outputTokens[0], callCounter[0]);
    }

    private void processBatch(List<TranslationSegment> batch, int depth, TranslationRequest request,
            String systemPrompt, List<TranslationSegment> working, Budget budget, int[] callCounter,
            int[] inputTokens, int[] outputTokens, List<String> warnings) {
        if (batch.isEmpty() || !budget.canCall()) {
            return;
        }
        String userPrompt = userPrompt(batch, request.sourceLanguage(), request.targetLanguage());
        LlmResult result;
        try {
            callCounter[0]++;
            budget.calls++;
            LlmProvider provider = request.resolved().provider();
            result = provider.generate(systemPrompt, userPrompt, request.resolved().options());
            inputTokens[0] += result.inputTokens();
            outputTokens[0] += result.outputTokens();
            budget.tokens += result.inputTokens() + result.outputTokens();
        } catch (RuntimeException e) {
            log.warn("Translation batch ({} segments) failed: {}", batch.size(), e.getMessage());
            if (batch.size() > 1 && depth < 6 && budget.canCall()) {
                split(batch).forEach(half -> processBatch(half, depth + 1, request, systemPrompt,
                        working, budget, callCounter, inputTokens, outputTokens, warnings));
            } else {
                fail(batch, working, e.getMessage());
                warnings.add("Не переведён фрагмент из " + batch.size() + " сегм.: " + e.getMessage());
            }
            return;
        }

        Map<Integer, String> translated = parse(result.content());
        boolean valid = translated != null && batch.stream()
                .allMatch(s -> translated.containsKey(s.sequence())
                        && translated.get(s.sequence()) != null
                        && !translated.get(s.sequence()).isBlank())
                && !duplicatedTranslations(batch, translated);
        if (!valid) {
            if (batch.size() > 1 && depth < 6 && budget.canCall()) {
                split(batch).forEach(half -> processBatch(half, depth + 1, request, systemPrompt,
                        working, budget, callCounter, inputTokens, outputTokens, warnings));
            } else {
                fail(batch, working, "LLM вернула неполный или некорректный JSON перевода");
                warnings.add("Не переведён сегмент " + batch.getFirst().sequence()
                        + ": некорректный ответ модели");
            }
            return;
        }
        Map<Integer, TranslationSegment> bySequence = new HashMap<>();
        working.forEach(s -> bySequence.put(s.sequence(), s));
        for (TranslationSegment source : batch) {
            TranslationSegment current = bySequence.get(source.sequence());
            TranslationSegment updated = current.translated(translated.get(source.sequence()).strip(),
                    "OK", null, current.attempts() + 1);
            replace(working, updated);
        }
    }

    /**
     * Detects a model alignment failure where several different source segments
     * receive the same translation (the local 1.7B model tends to repeat the
     * first item of a batch). Such a batch is rejected and split into smaller
     * ones instead of silently mixing content.
     */
    static boolean duplicatedTranslations(List<TranslationSegment> batch,
            Map<Integer, String> translated) {
        Map<String, String> byTranslation = new HashMap<>();
        for (TranslationSegment source : batch) {
            String text = translated.get(source.sequence());
            if (text == null) {
                continue;
            }
            String normalized = text.strip().toLowerCase(java.util.Locale.ROOT);
            String previousSource = byTranslation.putIfAbsent(normalized,
                    source.sourceText().strip().toLowerCase(java.util.Locale.ROOT));
            if (previousSource != null && !previousSource.equals(
                    source.sourceText().strip().toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void fail(List<TranslationSegment> batch, List<TranslationSegment> working,
            String error) {
        for (TranslationSegment source : batch) {
            TranslationSegment current = working.stream()
                    .filter(s -> s.sequence() == source.sequence()).findFirst().orElse(source);
            replace(working, current.failed(error, current.attempts() + 1));
        }
    }

    private static void replace(List<TranslationSegment> working, TranslationSegment updated) {
        for (int i = 0; i < working.size(); i++) {
            if (working.get(i).sequence() == updated.sequence()) {
                working.set(i, updated);
                return;
            }
        }
    }

    private static List<List<TranslationSegment>> split(List<TranslationSegment> batch) {
        int middle = batch.size() / 2;
        return List.of(batch.subList(0, middle), batch.subList(middle, batch.size()));
    }

    private List<List<TranslationSegment>> batch(List<TranslationSegment> pending) {
        List<List<TranslationSegment>> result = new ArrayList<>();
        List<TranslationSegment> current = new ArrayList<>();
        int chars = 0;
        for (TranslationSegment segment : pending) {
            int length = segment.sourceText().length();
            boolean full = !current.isEmpty()
                    && (chars + length > properties.maxBatchChars()
                            || current.size() >= properties.maxSegmentsPerBatch());
            if (full) {
                result.add(current);
                current = new ArrayList<>();
                chars = 0;
            }
            current.add(segment);
            chars += length;
        }
        if (!current.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    private String userPrompt(List<TranslationSegment> batch, String sourceLanguage,
            String targetLanguage) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("source_language", sourceLanguage);
        root.put("target_language", targetLanguage);
        ArrayNode segments = root.putArray("segments");
        for (TranslationSegment segment : batch) {
            ObjectNode node = segments.addObject();
            node.put("id", segment.sequence());
            node.put("text", segment.sourceText());
        }
        return objectMapper.writeValueAsString(root);
    }

    /** Parses {@code {"segments":[...]}} or a bare array; returns null when invalid. */
    Map<Integer, String> parse(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String cleaned = content.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        int firstObject = cleaned.indexOf('{');
        int firstArray = cleaned.indexOf('[');
        try {
            JsonNode root;
            if (firstArray >= 0 && (firstObject < 0 || firstArray < firstObject)) {
                root = objectMapper.readTree(cleaned.substring(firstArray,
                        cleaned.lastIndexOf(']') + 1));
            } else if (firstObject >= 0) {
                root = objectMapper.readTree(cleaned.substring(firstObject,
                        cleaned.lastIndexOf('}') + 1));
            } else {
                return null;
            }
            JsonNode array = root.isArray() ? root : root.path("segments");
            if (!array.isArray()) {
                return null;
            }
            Map<Integer, String> result = new LinkedHashMap<>();
            for (JsonNode node : array) {
                int id = node.path("id").isNumber() ? node.get("id").asInt()
                        : Integer.parseInt(node.path("id").asText("-1"));
                String text = node.path("text").asText("");
                if (id < 0 || result.containsKey(id)) {
                    return null;
                }
                result.put(id, text);
            }
            return result;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * One bounded retry for flagged segments. Successfully translated segments are
     * never re-sent; only suspicious ones are retried individually, and a failed
     * retry keeps the original warning.
     */
    private void retrySuspiciousSegments(List<TranslationSegment> working,
            TranslationRequest request, String systemPrompt, Budget budget, int[] callCounter,
            int[] inputTokens, int[] outputTokens, List<String> warnings) {
        List<TranslationSegment> suspicious = working.stream()
                .filter(s -> s.status() == SegmentTranslationStatus.WARNING)
                .toList();
        for (TranslationSegment segment : suspicious) {
            if (!budget.canCall()) {
                warnings.add("Часть сомнительных сегментов не перепроверена: лимит LLM-вызовов");
                return;
            }
            processBatch(List.of(segment), 0, request, systemPrompt, working, budget, callCounter,
                    inputTokens, outputTokens, warnings);
        }
    }

    private void applyQualityChecks(List<TranslationSegment> working, String sourceLanguage,
            String targetLanguage) {
        List<GlossaryEntry> entries = glossary.entries(sourceLanguage, targetLanguage);
        for (int i = 0; i < working.size(); i++) {
            TranslationSegment segment = working.get(i);
            if (!segment.done() || segment.humanOwned()) {
                // Human corrections and explicit reviews are never re-judged by
                // the automatic validator: their state belongs to the user.
                continue;
            }
            String warning = qualityWarning(segment.sourceText(), segment.translatedText(), entries,
                    targetLanguage);
            if (warning != null) {
                working.set(i, new TranslationSegment(segment.sequence(), segment.startMs(),
                        segment.endMs(), segment.sourceText(), segment.translatedText(),
                        SegmentTranslationStatus.WARNING, warning, warning, null,
                        segment.attempts()));
            }
        }
    }

    /**
     * Cheap, explainable quality checks. These are warnings, never a fabricated
     * semantic score: a human (or a retry) decides what to do with them.
     */
    static String qualityWarning(String source, String translated, List<GlossaryEntry> glossary,
            String targetLanguage) {
        if (translated == null || translated.isBlank()) {
            return "пустой перевод";
        }
        if ("ru".equals(targetLanguage)) {
            int cyrillic = countCyrillic(translated);
            int latin = countLatin(translated);
            if (cyrillic == 0) {
                return "перевод не содержит кириллицы";
            }
            if (latin > 0 && cyrillic * 2 < latin) {
                return "перевод содержит мало кириллицы (возможно, текст не переведён)";
            }
        }
        List<String> missingNumbers = missingNumbers(source, translated);
        if (!missingNumbers.isEmpty()) {
            return "потеряны числа: " + String.join(", ", missingNumbers);
        }
        for (GlossaryEntry entry : glossary) {
            if (!containsTerm(source, entry) || entry.term().equalsIgnoreCase(entry.translation())) {
                continue;
            }
            if (!containsTerm(translated, new GlossaryEntry(entry.id(),
                    entry.translation(), entry.translation(), entry.sourceLanguage(),
                    entry.targetLanguage(), entry.caseSensitive(), true, true, entry.updatedAt()))) {
                return "термин «" + entry.term() + "» переведён не по глоссарию";
            }
        }
        double ratio = (double) translated.length() / Math.max(1, source.length());
        if (ratio < 0.25 || ratio > 3.0) {
            return "подозрительная длина перевода";
        }
        return null;
    }

    private static boolean containsTerm(String text, GlossaryEntry entry) {
        if (entry.caseSensitive()) {
            return text.contains(entry.term());
        }
        return text.toLowerCase(java.util.Locale.ROOT)
                .contains(entry.term().toLowerCase(java.util.Locale.ROOT));
    }

    private static int countCyrillic(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'А' && c <= 'я' || c == 'Ё' || c == 'ё') {
                count++;
            }
        }
        return count;
    }

    private static int countLatin(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') {
                count++;
            }
        }
        return count;
    }

    private static List<String> missingNumbers(String source, String translated) {
        Map<String, Integer> expected = numbers(source);
        Map<String, Integer> actual = numbers(translated);
        List<String> missing = new ArrayList<>();
        expected.forEach((number, count) -> {
            if (actual.getOrDefault(number, 0) < count) {
                missing.add(number);
            }
        });
        return missing;
    }

    private static Map<String, Integer> numbers(String text) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            String value = matcher.group().replace(',', '.');
            counts.merge(value, 1, Integer::sum);
        }
        return counts;
    }

    private LectureTranslation writeProgress(LectureTranslation translation,
            List<TranslationSegment> working, List<String> warnings, int llmCalls, int inputTokens,
            int outputTokens, LlmRunConfig config, boolean cloud, TranslationStatus status) {
        int translated = (int) working.stream().filter(TranslationSegment::done).count();
        int failed = working.size() - translated;
        CloudCostService.CostEstimate estimate = costService.estimate(inputTokens, outputTokens, 0, 0);
        LectureTranslation updated = new LectureTranslation(translation.id(),
                translation.lectureId(), translation.sourceFingerprint(),
                translation.sourceLanguage(), translation.targetLanguage(), translation.provider(),
                translation.model(), translation.promptVersion(), translation.glossaryVersion(),
                translation.settingsHash(), status, working.size(), translated, failed,
                warnings, status == TranslationStatus.FAILED && !warnings.isEmpty()
                        ? warnings.getLast() : null,
                inputTokens, outputTokens, llmCalls,
                cloud && estimate.priced() ? estimate.amount() : null,
                cloud ? estimate.currency() : null, translation.createdAt(),
                java.time.Instant.now());
        repository.updateStatus(updated);
        return updated;
    }

    private TranslationOutcome resumed(LectureTranslation translation,
            List<TranslationSegment> working, LlmRunConfig config) {
        int translated = (int) working.stream().filter(TranslationSegment::done).count();
        int failed = working.size() - translated;
        TranslationStatus status = failed == 0 ? TranslationStatus.COMPLETED
                : translated > 0 ? TranslationStatus.PARTIAL : TranslationStatus.FAILED;
        return new TranslationOutcome(translation, working, status, working.size(), translated,
                failed, translation.warnings(), translation.inputTokens() == null ? 0
                        : translation.inputTokens(),
                translation.outputTokens() == null ? 0 : translation.outputTokens(),
                translation.llmCalls());
    }

    private String settingsHash(LlmRunConfig config, TranslationRequest request,
            String glossaryVersion) {
        return Fingerprints.of("translation", config.provider(), config.model(),
                String.valueOf(config.temperature()), String.valueOf(config.maxTokens()),
                String.valueOf(config.contextWindow()), String.valueOf(config.thinking()),
                properties.promptVersion(), glossaryVersion, request.sourceLanguage(),
                request.targetLanguage(), String.valueOf(properties.maxBatchChars()),
                String.valueOf(properties.maxSegmentsPerBatch()),
                request.sourceFingerprint()).substring(0, 40);
    }

    /** Fingerprint of the source segments used for checkpointing. */
    public static String sourceFingerprint(List<TranscriptSegment> segments) {
        String material = segments.stream()
                .map(s -> s.sequence() + ":" + s.startMs() + ":" + s.endMs() + ":" + s.text())
                .reduce("", (a, b) -> a + "|" + b);
        return FileChecksum.sha256(material);
    }

    public record TranslationRequest(
            String lectureId,
            long translationId,
            List<TranscriptSegment> sourceSegments,
            String sourceFingerprint,
            String sourceLanguage,
            String targetLanguage,
            ResolvedLlmTask resolved,
            CancellationToken cancellation) {
    }

    public record TranslationOutcome(
            LectureTranslation translation,
            List<TranslationSegment> segments,
            TranslationStatus status,
            int total,
            int translated,
            int failed,
            List<String> warnings,
            int inputTokens,
            int outputTokens,
            int llmCalls) {
    }

    public record StoredTranslation(LectureTranslation translation,
            List<TranslationSegment> segments) {
    }

    private static final class Budget {
        private final int maxCalls;
        private final int maxTokens;
        private int calls;
        private int tokens;

        Budget(int maxCalls, int maxTokens) {
            this.maxCalls = maxCalls;
            this.maxTokens = maxTokens;
        }

        boolean canCall() {
            return calls < maxCalls && tokens < maxTokens;
        }
    }
}
