package com.lectureprocessor.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.llm.LlmOptions;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.TestProperties;

class TextProcessingServiceTest {

    private final FakeLlmProvider llm = new FakeLlmProvider();
    private final PromptTemplates prompts = new PromptTemplates();
    private final TextChunker chunker = new TextChunker();
    private final TextProcessingService service = new TextProcessingService(llm, chunker, prompts,
            new NotesAssembler(), TestProperties.defaults("./target/test-data"));

    private final List<TranscriptSegment> segments = List.of(
            new TranscriptSegment(0, 0, 1000, "Определение: вектор — это направленный отрезок."),
            new TranscriptSegment(1, 1000, 2000, "Далее рассмотрим скалярное произведение и его свойства."));

    @Test
    void cleansTranscriptUsingLlm() {
        llm.setChatFunction((system, user) -> "Исправленный фрагмент.");
        String cleaned = service.cleanTranscript(segments, LlmOptions.of("fake-model"));
        assertThat(cleaned).contains("Исправленный фрагмент.");
    }

    @Test
    void buildsStructuredNotesWithSummary() {
        llm.setChatFunction((system, user) -> "## Раздел\n\nТекст конспекта.");
        StructuredNotes notes = service.generateStructuredNotes(segments, LlmOptions.of("fake-model"));

        assertThat(notes.markdown()).contains("# Конспект лекции");
        assertThat(notes.markdown()).contains("## Резюме");
        assertThat(notes.sections()).isNotEmpty();
        assertThat(notes.sections().getFirst().startMs()).isZero();
    }

    @Test
    void passesFormattingInstructionsInTheSystemPromptAndKeepsTranscriptAsData() {
        java.util.List<String> userMessages = new java.util.ArrayList<>();
        java.util.List<String> systemMessages = new java.util.ArrayList<>();
        llm.setChatFunction((system, user) -> {
            systemMessages.add(system);
            userMessages.add(user);
            return "## Раздел\n\nТекст.";
        });

        service.generateStructuredNotes(llm, segments, LlmOptions.of("fake-model"),
                PromptTemplates.NOTES_HOWTO, "Тип исходного материала: Практический видеоурок.");

        assertThat(userMessages).isNotEmpty();
        assertThat(systemMessages.getFirst())
                .contains("Тип исходного материала: Практический видеоурок.")
                .contains("ПАРАМЕТРЫ ЗАДАНИЯ");
        assertThat(userMessages.getFirst())
                .contains("<transcript>")
                .contains("</transcript>")
                .doesNotContain("Тип исходного материала");
    }

    @Test
    void removesRepeatedQaBlocksAcrossChunks() {
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        llm.setChatFunction((system, user) -> {
            if (system != null && system.contains("краткое резюме")) {
                return "Краткое резюме лекции.";
            }
            int index = call.getAndIncrement();
            return """
                    ## Вопросы и ответы

                    **Вопрос:** Какой вариант выбран в сравнении с Baseline?

                    **Ответ:** Выбран вариант A, он лучше Baseline.

                    **Вопрос:** Уникальный вопрос %d?

                    **Ответ:** Уникальный ответ %d.
                    """.formatted(index, index);
        });
        List<TranscriptSegment> longSegments = List.of(
                new TranscriptSegment(0, 0, 1000, "Первый фрагмент про Baseline и вариант A. "
                        + "Подробное описание сравнения варианта A с Baseline и причины выбора."),
                new TranscriptSegment(1, 1000, 2000, "Второй фрагмент про Baseline и вариант A. "
                        + "Подробное описание сравнения варианта A с Baseline и причины выбора."),
                new TranscriptSegment(2, 2000, 3000, "Третий фрагмент продолжает обсуждение. "
                        + "Подробное описание сравнения варианта A с Baseline и причины выбора."));

        TextProcessingService.GeneratedNotes generated = service.generateStructuredNotes(llm,
                longSegments, LlmOptions.of("fake-model"), PromptTemplates.NOTES_INTERVIEW,
                null, "Формат результата: ВОПРОСЫ И ОТВЕТЫ.", ChunkProgressListener.NONE);

        long baselineQuestionCount = generated.notes().markdown().lines()
                .filter(line -> line.contains("Baseline?")).count();
        assertThat(baselineQuestionCount).as(generated.notes().markdown()).isEqualTo(1);
        assertThat(generated.quality().duplicateBlocksRemoved()
                + generated.quality().duplicateQuestionsRemoved()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void cleanTranscriptDocumentKeepsTimeSpansAndPlainText() {
        llm.setChatFunction((system, user) -> "Очищенный текст.");

        TextProcessingService.CleanedTranscriptDocument document =
                service.cleanTranscriptDocument(segments, LlmOptions.of("fake-model"),
                        PromptTemplates.CLEAN_TRANSCRIPT_V2);

        assertThat(document.cleanedText()).contains("Очищенный текст.");
        assertThat(document.notes().markdown()).startsWith("# Расшифровка лекции");
        assertThat(document.notes().sections()).isNotEmpty();
        assertThat(document.notes().sections().getFirst().startMs()).isZero();
        assertThat(document.notes().summary()).isEmpty();
    }

    @Test
    void rejectsEmptyLlmOutput() {
        llm.setChatFunction((system, user) -> "   ");
        assertThatThrownBy(() -> service.cleanTranscript(segments, LlmOptions.of("fake-model")))
                .isInstanceOf(ProcessingException.class);
    }
}
