package com.lectureprocessor.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class NotesAssemblerTest {

    private final NotesAssembler assembler = new NotesAssembler();

    private static final String QA = """
            ## Вопросы и ответы

            **Вопрос:** Какой вариант выбран в сравнении с Baseline?

            **Ответ:** Выбран вариант A.
            """;

    @Test
    void mergesIdenticalHeadingsAndDropsDuplicateQuestions() {
        List<SegmentChunk> chunks = List.of(
                new SegmentChunk(0, 0, 1000, "a"),
                new SegmentChunk(1, 1000, 2000, "b"));

        NotesAssembler.Assembly assembly = assembler.assemble(chunks, List.of(QA, QA));

        assertThat(assembly.sections()).hasSize(1);
        assertThat(assembly.markdown().lines()
                .filter(line -> line.contains("Baseline?")).count()).isEqualTo(1);
        assertThat(assembly.report().duplicateQuestionsRemoved()
                + assembly.report().duplicateBlocksRemoved()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void removesEchoedInstructionsAndRepeatedBullets() {
        String output = """
                ## ПРАВИЛА ДОСТОВЕРНОСТИ
                - Используй только содержание фрагмента.

                ## Ключевые идеи
                - Термин A определён в тексте.
                - Термин A определён в тексте.
                - Второй тезис.
                """;

        NotesAssembler.Assembly assembly = assembler.assemble(
                List.of(new SegmentChunk(0, 0, 1000, "a")), List.of(output));

        assertThat(assembly.markdown()).doesNotContain("ПРАВИЛА ДОСТОВЕРНОСТИ");
        assertThat(assembly.markdown().lines()
                .filter(line -> line.contains("Термин A")).count()).isEqualTo(1);
        assertThat(assembly.report().promptLeakLinesRemoved()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void keepsLegitimateRepeatedTechnicalTerms() {
        String output = """
                ## Определения
                - HTTP — протокол передачи гипертекста.
                - REST использует HTTP как транспортный протокол.
                """;

        NotesAssembler.Assembly assembly = assembler.assemble(
                List.of(new SegmentChunk(0, 0, 1000, "a")), List.of(output));

        assertThat(assembly.markdown()).contains("HTTP — протокол")
                .contains("REST использует HTTP");
    }
}
