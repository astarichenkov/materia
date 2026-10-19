package com.lectureprocessor.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.text.NoteSection;
import com.lectureprocessor.text.StructuredNotes;

/**
 * Builds the deliverable for scenarios that must not call an LLM (verbatim and
 * translation-only).
 *
 * <p>The text is not paraphrased, cleaned up, deduplicated or reordered. Each
 * segment keeps its real timestamp and its original order. Only the presentation
 * is normalised: one line per segment, an optional timestamp prefix and section
 * boundaries aligned with the source time span (so slides still line up).</p>
 */
@Component
public class TranscriptDocumentBuilder {

    private static final int SEGMENTS_PER_SECTION = 40;

    public record Result(StructuredNotes notes, List<String> warnings) {
    }

    public Result build(ProcessingScenario scenario, List<TranscriptSegment> segments,
            boolean translated) {
        List<TranscriptSegment> source = segments == null ? List.of() : segments;
        List<String> warnings = new ArrayList<>();
        String label = scenario == null ? "Транскрипция" : scenario.documentLabel();
        StringBuilder markdown = new StringBuilder("# ").append(label).append("\n\n");
        if (scenario == ProcessingScenario.TRANSLATION) {
            markdown.append(translated
                    ? "_Перевод. Оригинал доступен в разделе «Оригинал»._"
                    : "_Перевод не был выполнен: показан исходный текст._");
            markdown.append("\n\n");
            if (!translated) {
                warnings.add("Перевод не найден: документ собран из оригинальной транскрипции. "
                        + "Включите перевод в настройках языка и запустите обработку заново.");
            }
        }
        if (source.isEmpty()) {
            warnings.add("Транскрипция пуста: документ без содержания.");
            return new Result(new StructuredNotes(markdown.toString().strip(), "", List.of()),
                    warnings);
        }

        List<NoteSection> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        long sectionStart = source.getFirst().startMs();
        long sectionEnd = sectionStart;
        for (int i = 0; i < source.size(); i++) {
            TranscriptSegment segment = source.get(i);
            current.append('[').append(timestamp(segment.startMs())).append("] ");
            String text = segment.text() == null ? "" : segment.text().strip();
            current.append(text.isEmpty() ? "[неразборчиво]" : text).append("\n\n");
            sectionEnd = segment.endMs();
            boolean last = i == source.size() - 1;
            if (last || (i + 1) % SEGMENTS_PER_SECTION == 0) {
                String block = current.toString().strip();
                sections.add(new NoteSection(sections.size() + 1, sectionStart, sectionEnd,
                        block));
                markdown.append("## ").append(label).append(" · ")
                        .append(timestamp(sectionStart)).append("–")
                        .append(timestamp(sectionEnd)).append("\n\n")
                        .append(block).append("\n\n");
                current.setLength(0);
                if (!last) {
                    sectionStart = source.get(i + 1).startMs();
                }
            }
        }
        return new Result(new StructuredNotes(markdown.toString().strip(), "", sections), warnings);
    }

    /** Plain text of the document without timestamps, used by tests/exports. */
    public static String plainText(StructuredNotes notes) {
        if (notes == null || notes.markdown() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String line : notes.markdown().split("\\R")) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            stripped = stripped.replaceFirst("^\\[\\d{2}:\\d{2}:\\d{2}]\\s*", "");
            stripped = stripped.replaceFirst("^_.*_$", "");
            if (!stripped.isBlank()) {
                text.append(stripped).append(' ');
            }
        }
        return text.toString().strip();
    }

    static String timestamp(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
    }
}
