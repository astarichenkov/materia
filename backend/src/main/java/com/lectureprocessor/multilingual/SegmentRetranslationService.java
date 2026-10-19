package com.lectureprocessor.multilingual;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.llm.LlmProvider;
import com.lectureprocessor.llm.LlmResult;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.text.PromptTemplates;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Re-translates a single segment using the configured
 * {@link LlmTaskType#LECTURE_TRANSLATION} model. Neighbouring segments and the
 * glossary are supplied as context. The result is only a suggestion; the editor
 * service decides whether to store it (never overwriting a human revision
 * without explicit acceptance).
 */
@Service
public class SegmentRetranslationService {

    private static final Logger log = LoggerFactory.getLogger(SegmentRetranslationService.class);
    private static final int MAX_GLOSSARY_PROMPT_CHARS = 4000;
    private static final int NEIGHBOUR_CONTEXT = 2;

    private final TranslationRepository translations;
    private final TranslationService translationService;
    private final TranslationGlossaryService glossary;
    private final LlmTaskResolver taskResolver;
    private final PromptTemplates prompts;
    private final MultilingualProperties properties;
    private final ObjectMapper objectMapper;

    public SegmentRetranslationService(TranslationRepository translations,
            TranslationService translationService, TranslationGlossaryService glossary,
            LlmTaskResolver taskResolver, PromptTemplates prompts,
            MultilingualProperties properties, ObjectMapper objectMapper) {
        this.translations = translations;
        this.translationService = translationService;
        this.glossary = glossary;
        this.taskResolver = taskResolver;
        this.prompts = prompts;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public RetranslationSuggestion suggest(String lectureId, int sequence) {
        LectureTranslation translation = translations.findLatest(lectureId)
                .orElseThrow(() -> EditorRetranslationException.unavailable(
                        "Для этого материала ещё нет перевода"));
        List<TranslationSegment> segments = translations.segments(translation.id());
        TranslationSegment target = segments.stream()
                .filter(s -> s.sequence() == sequence).findFirst()
                .orElseThrow(() -> EditorRetranslationException.notFound(
                        "Сегмент " + sequence + " не найден в переводе"));
        ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.LECTURE_TRANSLATION);
        if (!resolved.provider().isAvailable()) {
            throw EditorRetranslationException.unavailable(
                    "Провайдер перевода недоступен: " + resolved.providerId());
        }
        String sourceLanguage = translation.sourceLanguage() == null ? "en"
                : translation.sourceLanguage();
        String targetLanguage = translation.targetLanguage() == null ? "ru"
                : translation.targetLanguage();
        String systemPrompt = prompts.get(properties.promptTemplate())
                .replace("{{GLOSSARY}}", glossary.promptBlock(sourceLanguage, targetLanguage,
                        MAX_GLOSSARY_PROMPT_CHARS));
        List<TranslationSegment> context = context(segments, sequence);
        String userPrompt = userPrompt(context, sequence, sourceLanguage, targetLanguage);
        LlmProvider provider = resolved.provider();
        LlmResult result = provider.generate(systemPrompt, userPrompt, resolved.options());
        Map<Integer, String> parsed = translationService.parse(result.content());
        String candidate = parsed == null ? null : parsed.get(sequence);
        if (candidate == null || candidate.isBlank()) {
            throw EditorRetranslationException.unavailable(
                    "Модель вернула некорректный ответ; повторите попытку позже");
        }
        String warning = TranslationService.qualityWarning(target.sourceText(), candidate.strip(),
                glossary.entries(sourceLanguage, targetLanguage), targetLanguage);
        log.info("Retranslation suggestion for lecture {} segment {} from {}: warning={}",
                lectureId, sequence, resolved.providerId(), warning);
        return new RetranslationSuggestion(sequence, target.translatedText(), candidate.strip(),
                resolved.providerId(), resolved.model(), warning == null ? "OK" : warning,
                warning, resolved.cloud(), result.inputTokens(), result.outputTokens());
    }

    private static List<TranslationSegment> context(List<TranslationSegment> segments, int sequence) {
        List<TranslationSegment> ordered = new ArrayList<>();
        for (TranslationSegment segment : segments) {
            if (Math.abs(segment.sequence() - sequence) <= NEIGHBOUR_CONTEXT) {
                ordered.add(segment);
            }
        }
        if (ordered.stream().noneMatch(s -> s.sequence() == sequence)) {
            ordered.addAll(segments.stream().filter(s -> s.sequence() == sequence).toList());
        }
        return ordered;
    }

    private String userPrompt(List<TranslationSegment> context, int targetSequence,
            String sourceLanguage, String targetLanguage) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("source_language", sourceLanguage);
        root.put("target_language", targetLanguage);
        root.put("focus_segment_id", targetSequence);
        root.put("instruction",
                "Translate every segment, but the result is used only for focus_segment_id. "
                        + "Use the neighbouring segments as context.");
        ArrayNode segments = root.putArray("segments");
        for (TranslationSegment segment : context) {
            ObjectNode node = segments.addObject();
            node.put("id", segment.sequence());
            node.put("text", segment.sourceText());
        }
        return objectMapper.writeValueAsString(root);
    }

    /** Map of sequence -> source text, kept for deterministic ordering. */
    static Map<Integer, String> indexBySequence(List<TranslationSegment> segments) {
        Map<Integer, String> result = new LinkedHashMap<>();
        segments.forEach(segment -> result.put(segment.sequence(), segment.sourceText()));
        return result;
    }
}
