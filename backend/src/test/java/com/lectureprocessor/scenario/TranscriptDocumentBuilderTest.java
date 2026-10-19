package com.lectureprocessor.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.text.StructuredNotes;

class TranscriptDocumentBuilderTest {

    private final TranscriptDocumentBuilder builder = new TranscriptDocumentBuilder();

    private final List<TranscriptSegment> segments = List.of(
            new TranscriptSegment(0, 0, 1000, "Привет!"),
            new TranscriptSegment(1, 1000, 2000, "Привет!"),
            new TranscriptSegment(2, 2000, 3000, "Как дела?"));

    @Test
    void verbatimKeepsOrderRepeatsAndTimestamps() {
        TranscriptDocumentBuilder.Result result = builder.build(ProcessingScenario.VERBATIM,
                segments, false);

        String markdown = result.notes().markdown();
        assertThat(markdown).startsWith("# Транскрипция");
        assertThat(markdown).contains("[00:00:00] Привет!")
                .contains("[00:00:01] Привет!")
                .contains("[00:00:02] Как дела?");
        assertThat(markdown.indexOf("Как дела?"))
                .isGreaterThan(markdown.lastIndexOf("Привет!"));
        assertThat(result.warnings()).isEmpty();
        assertThat(TranscriptDocumentBuilder.plainText(result.notes()))
                .isEqualTo("Привет! Привет! Как дела?");
    }

    @Test
    void verbatimTextContainsEverySegmentExactlyOnce() {
        TranscriptDocumentBuilder.Result result = builder.build(ProcessingScenario.VERBATIM,
                segments, false);
        for (TranscriptSegment segment : segments) {
            assertThat(result.notes().markdown()).contains(segment.text());
        }
        assertThat(TranscriptDocumentBuilder.plainText(result.notes()).split("Привет!").length)
                .isEqualTo(3);
    }

    @Test
    void translationOnlyWarnsWhenTranslationIsMissing() {
        TranscriptDocumentBuilder.Result result = builder.build(ProcessingScenario.TRANSLATION,
                segments, false);

        assertThat(result.notes().markdown()).startsWith("# Перевод");
        assertThat(result.warnings()).anyMatch(w -> w.contains("Перевод не найден"));
    }

    @Test
    void translationOnlyUsesTranslatedSegmentsWhenAvailable() {
        List<TranscriptSegment> translated = List.of(
                new TranscriptSegment(0, 0, 1000, "Здравствуйте!"),
                new TranscriptSegment(1, 1000, 2000, "Как вы?"));
        TranscriptDocumentBuilder.Result result = builder.build(ProcessingScenario.TRANSLATION,
                translated, true);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.notes().markdown()).contains("Здравствуйте!").contains("Как вы?");
        assertThat(TranscriptDocumentBuilder.plainText(result.notes()))
                .isEqualTo("Здравствуйте! Как вы?");
    }

    @Test
    void emptyTranscriptProducesAWarningNotAnEmptySuccess() {
        TranscriptDocumentBuilder.Result result = builder.build(ProcessingScenario.VERBATIM,
                List.of(), false);
        assertThat(result.warnings()).anyMatch(w -> w.contains("Транскрипция пуста"));
        assertThat(result.notes()).isNotNull();
    }
}
