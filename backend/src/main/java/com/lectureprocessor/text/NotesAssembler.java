package com.lectureprocessor.text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Assembles chunk outputs into one coherent document.
 *
 * <p>The old implementation concatenated every chunk verbatim: nine chunks meant
 * nine "## Вопросы и ответы" sections with the same question repeated. This
 * component merges sections by heading, removes exact and near-duplicate
 * paragraphs/bullets/questions, strips echoed instructions (prompt leakage) and
 * reports what it changed. It deliberately keeps legitimate repeated terms —
 * only blocks that are (almost) identical are removed.</p>
 */
@Component
public class NotesAssembler {

    private static final Pattern HEADING = Pattern.compile("^(#{1,4})\\s+(.+?)\\s*$");
    private static final Pattern QUESTION = Pattern.compile(
            "^(?:\\*\\*)?(?:вопрос|вопрос\\s*\\d+)?\\s*[:.)\\-]?\\s*(.+?\\?)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*•]\\s+(.+)$");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final List<String> PROMPT_LEAK_MARKERS = List.of(
            "правила достоверности", "тип исходного материала", "степень детализации",
            "формат результата:", "фрагмент 1 из", "фрагмент 2 из", "фрагмент 3 из",
            "не отвечай", "верни только markdown", "системная инструкция");

    private static final Set<String> IGNORED_HEADINGS = Set.of(
            "конспект лекции", "конспект", "результат", "ответ", "output");

    /** Placeholder claims that add no content and should not become sections. */
    private static final Set<String> EMPTY_CLAIMS = Set.of(
            "нет разногласий", "разногласий нет", "нет данных", "нет информации",
            "не указано", "отсутствуют", "нет", "не обнаружено");

    /** Result of the assembly; sections keep the time span of their first chunk. */
    public record Assembly(String markdown, List<NoteSection> sections,
            TextQualityReport report) {
    }

    private static final class SectionBuilder {
        private final String heading;
        private final long startMs;
        private long endMs;
        private final List<String> paragraphs = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();

        private SectionBuilder(String heading, long startMs, long endMs) {
            this.heading = heading;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    public Assembly assemble(List<SegmentChunk> chunks, List<String> outputs) {
        Map<String, SectionBuilder> sections = new LinkedHashMap<>();
        Set<String> globalParagraphs = new HashSet<>();
        Set<String> recentKeys = new LinkedHashSet<>();
        Set<String> seenQuestions = new HashSet<>();
        Set<String> seenSentences = new HashSet<>();
        int duplicateBlocks = 0;
        int duplicateQuestions = 0;
        int promptLeakLines = 0;
        int truncated = 0;
        int repeatedSentences = 0;

        for (int i = 0; i < outputs.size(); i++) {
            SegmentChunk chunk = i < chunks.size() ? chunks.get(i)
                    : new SegmentChunk(i, 0, 0, "");
            String output = outputs.get(i) == null ? "" : outputs.get(i);
            if (isTruncated(output)) {
                truncated++;
            }
            List<Block> blocks = splitBlocks(output);
            if (blocks.isEmpty()) {
                continue;
            }
            for (Block block : blocks) {
                String heading = normalizeHeading(block.heading());
                if (heading == null) {
                    heading = "Основное";
                }
                if (leakKind(heading) != null) {
                    // The model echoed a heading from the instructions: never a
                    // section of the user's document.
                    promptLeakLines++;
                    continue;
                }
                SectionBuilder section = sections.computeIfAbsent(heading,
                        key -> new SectionBuilder(block.heading() == null ? "Основное" : block.heading().strip(),
                                chunk.startMs(), chunk.endMs()));
                section.endMs = Math.max(section.endMs, chunk.endMs());
                for (String paragraph : splitParagraphs(block.body())) {
                    String leak = leakKind(paragraph);
                    if (leak != null) {
                        promptLeakLines++;
                        continue;
                    }
                    if (paragraph.isBlank()) {
                        continue;
                    }
                    String key = normalizeKey(paragraph);
                    if (key.length() < 3 || EMPTY_CLAIMS.contains(key)) {
                        continue;
                    }
                    if (section.seen.contains(key) || globalParagraphs.contains(key)) {
                        duplicateBlocks++;
                        continue;
                    }
                    if (isQuestion(paragraph)) {
                        String questionKey = questionKey(paragraph);
                        if (questionKey != null && !seenQuestions.add(questionKey)) {
                            duplicateQuestions++;
                            continue;
                        }
                    }
                    if (isNearDuplicate(key, recentKeys)) {
                        duplicateBlocks++;
                        continue;
                    }
                    if (isBullet(paragraph)) {
                        List<String> bullets = dedupeBullets(paragraph);
                        if (bullets.isEmpty()) {
                            duplicateBlocks++;
                            continue;
                        }
                        paragraph = String.join("\n", bullets);
                    }
                    section.seen.add(key);
                    section.paragraphs.add(paragraph.strip());
                    globalParagraphs.add(key);
                    recentKeys.add(key);
                    if (recentKeys.size() > 400) {
                        var iterator = recentKeys.iterator();
                        iterator.next();
                        iterator.remove();
                    }
                }
            }
            repeatedSentences += countRepeatedSentences(output, seenSentences);
        }

        int sectionCount = 0;
        List<NoteSection> noteSections = new ArrayList<>();
        StringBuilder markdown = new StringBuilder();
        for (SectionBuilder section : sections.values()) {
            if (section.paragraphs.isEmpty()) {
                continue;
            }
            String body = String.join("\n\n", section.paragraphs);
            String block = "## " + section.heading.strip() + "\n\n" + body;
            markdown.append(block).append("\n\n");
            noteSections.add(new NoteSection(noteSections.size() + 1, section.startMs,
                    section.endMs, block));
            sectionCount++;
        }

        List<String> warnings = new ArrayList<>();
        if (duplicateBlocks > 0) {
            warnings.add("Удалено повторяющихся блоков: " + duplicateBlocks
                    + ". Причина: одинаковые фрагменты в соседних чанках.");
        }
        if (duplicateQuestions > 0) {
            warnings.add("Удалено повторяющихся вопросов: " + duplicateQuestions
                    + ". Вопрос задавался в материале несколько раз.");
        }
        if (promptLeakLines > 0) {
            warnings.add("Удалены строки, похожие на эхо инструкций модели: " + promptLeakLines + ".");
        }
        if (truncated > 0) {
            warnings.add("Часть чанков выглядит обрезанной (" + truncated
                    + "). Проверьте полноту документа.");
        }
        if (repeatedSentences > 3) {
            warnings.add("Обнаружены повторяющиеся предложения: " + repeatedSentences + ".");
        }
        if (sectionCount == 0) {
            warnings.add("Модель не вернула содержательных разделов.");
        }
        TextQualityReport report = new TextQualityReport(chunks.size(), sectionCount,
                duplicateBlocks, duplicateQuestions, promptLeakLines, truncated,
                repeatedSentences, warnings);
        return new Assembly(markdown.toString().strip(), noteSections, report);
    }

    private record Block(String heading, String body) {
    }

    private List<Block> splitBlocks(String markdown) {
        List<Block> blocks = new ArrayList<>();
        String currentHeading = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\\R")) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                if (currentHeading != null || !body.toString().isBlank()) {
                    blocks.add(new Block(currentHeading, body.toString()));
                }
                currentHeading = matcher.group(2).strip();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        if (currentHeading != null || !body.toString().isBlank()) {
            blocks.add(new Block(currentHeading, body.toString()));
        }
        return blocks;
    }

    private String normalizeHeading(String heading) {
        if (heading == null || heading.isBlank()) {
            return null;
        }
        String normalized = heading.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[#*:_`]+$", "")
                .replaceAll("\\s+", " ").strip();
        if (IGNORED_HEADINGS.contains(normalized) || normalized.startsWith("фрагмент ")) {
            return "Основное";
        }
        return normalized;
    }

    private List<String> splitParagraphs(String body) {
        List<String> paragraphs = new ArrayList<>();
        for (String candidate : body.split("\\n\\s*\\n")) {
            String stripped = candidate.strip();
            if (stripped.isEmpty()) {
                continue;
            }
            // A bullet list is one paragraph but may contain many repeated lines.
            paragraphs.add(stripped);
        }
        return paragraphs;
    }

    private boolean isBullet(String paragraph) {
        return paragraph.lines().anyMatch(line -> BULLET.matcher(line).matches());
    }

    private List<String> dedupeBullets(String paragraph) {
        Set<String> seen = new HashSet<>();
        List<String> kept = new ArrayList<>();
        for (String line : paragraph.split("\\R")) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                continue;
            }
            Matcher matcher = BULLET.matcher(line);
            if (matcher.matches()) {
                String key = normalizeKey(matcher.group(1));
                if (!seen.add(key)) {
                    continue;
                }
            }
            kept.add(stripped);
        }
        return kept;
    }

    private boolean isQuestion(String paragraph) {
        String stripped = paragraph.strip().replaceAll("^[-*•]\\s+", "");
        return stripped.endsWith("?") || stripped.toLowerCase(Locale.ROOT).startsWith("**вопрос");
    }

    private String questionKey(String paragraph) {
        String stripped = paragraph.strip().replaceAll("^[-*•]\\s+", "")
                .replaceAll("(?i)^\\*\\*вопрос:?\\s*\\*\\*\\s*", "")
                .replaceAll("^Вопрос\\s*\\d*\\s*[:.)\\-]?\\s*", "");
        String key = normalizeKey(stripped);
        return key.length() < 8 ? null : key;
    }

    private boolean isNearDuplicate(String key, Set<String> recentKeys) {
        Set<String> tokens = tokens(key);
        if (tokens.size() < 8 || key.length() < 70) {
            return false;
        }
        int checked = 0;
        for (String other : recentKeys) {
            if (++checked > 200) {
                break;
            }
            if (Math.abs(other.length() - key.length()) > Math.max(40, key.length() / 3)) {
                continue;
            }
            Set<String> otherTokens = tokens(other);
            if (otherTokens.size() < 8) {
                continue;
            }
            int intersection = 0;
            for (String token : tokens) {
                if (otherTokens.contains(token)) {
                    intersection++;
                }
            }
            int union = tokens.size() + otherTokens.size() - intersection;
            if (union > 0 && (double) intersection / union >= 0.85) {
                return true;
            }
        }
        return false;
    }

    private int countRepeatedSentences(String output, Set<String> globalParagraphs) {
        int repeated = 0;
        for (String sentence : output.split("(?<=[.!?…])\\s+")) {
            String key = normalizeKey(sentence);
            if (key.length() < 40) {
                continue;
            }
            if (!globalParagraphs.add(key)) {
                repeated++;
            }
        }
        return repeated;
    }

    private static Set<String> tokens(String normalized) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = WORD.matcher(normalized);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static String normalizeKey(String text) {
        return text.toLowerCase(Locale.ROOT).replace('\u00a0', ' ')
                .replaceAll("[\\p{Punct}«»“”„…—–]+", " ")
                .replaceAll("\\s+", " ").strip();
    }

    private static String leakKind(String paragraph) {
        String lower = paragraph.toLowerCase(Locale.ROOT);
        for (String marker : PROMPT_LEAK_MARKERS) {
            if (lower.contains(marker)) {
                return marker;
            }
        }
        return null;
    }

    private static boolean isTruncated(String output) {
        String stripped = output == null ? "" : output.strip();
        if (stripped.length() < 80) {
            return false;
        }
        char last = stripped.charAt(stripped.length() - 1);
        return ".!?…»\"`|)".indexOf(last) < 0;
    }
}
