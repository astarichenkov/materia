package com.lectureprocessor.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class NotesAssemblerPolicyTest {

    private final NotesAssembler assembler = new NotesAssembler();

    private static final List<SegmentChunk> CHUNKS = List.of(
            new SegmentChunk(0, 0, 1000, "a"),
            new SegmentChunk(1, 1000, 2000, "b"));

    @Test
    void semanticPolicyStillDeduplicatesBullets() {
        String output = """
                ## Шаги
                - Добавить 2 яйца.
                - Добавить 2 яйца.
                """;
        NotesAssembler.Assembly assembly = assembler.assemble(CHUNKS, List.of(output, ""));
        assertThat(assembly.markdown().lines().filter(l -> l.contains("2 яйца")).count())
                .isEqualTo(1);
    }

    @Test
    void structuredPolicyKeepsMeaningfulRepeatedIngredientsAndSteps() {
        String output = """
                ## Ингредиенты
                - 2 яйца
                - 2 яйца

                ## Приготовление
                1. Перемешать.
                1. Перемешать.
                """;
        NotesAssembler.Assembly assembly = assembler.assemble(CHUNKS,
                List.of(output, ""), AssemblyPolicy.STRUCTURED);

        assertThat(assembly.markdown().lines().filter(l -> l.contains("2 яйца")).count())
                .isEqualTo(2);
        assertThat(assembly.markdown().lines().filter(l -> l.contains("Перемешать")).count())
                .isEqualTo(2);
        assertThat(assembly.report().duplicateBlocksRemoved()).isZero();
    }

    @Test
    void chronologicalPolicyKeepsOrderAcrossChunks() {
        NotesAssembler.Assembly assembly = assembler.assemble(CHUNKS,
                List.of("## События\n- Первое событие.", "## События\n- Второе событие."),
                AssemblyPolicy.CHRONOLOGICAL);

        assertThat(assembly.markdown().indexOf("Первое"))
                .isLessThan(assembly.markdown().indexOf("Второе"));
        // Same heading is merged, but both blocks are kept.
        assertThat(assembly.markdown().lines().filter(l -> l.startsWith("## ")).count())
                .isEqualTo(1);
    }

    @Test
    void dialoguePolicyKeepsRepeatedLinesAndOrder() {
        String first = "## Диалог\n— Привет!\n— Как дела?";
        String second = "## Диалог\n— Привет!\n— Как дела?";
        NotesAssembler.Assembly assembly = assembler.assemble(CHUNKS,
                List.of(first, second), AssemblyPolicy.DIALOGUE);

        assertThat(assembly.markdown().split("Привет!", -1).length).isEqualTo(3);
        assertThat(assembly.sections()).hasSize(2);
        assertThat(assembly.report().duplicateBlocksRemoved()).isZero();
    }

    @Test
    void dialoguePolicyStillRemovesPromptEcho() {
        NotesAssembler.Assembly assembly = assembler.assemble(CHUNKS,
                List.of("Формат результата: ДИАЛОГ\n— Реплика."), AssemblyPolicy.DIALOGUE);
        assertThat(assembly.markdown()).doesNotContain("Формат результата");
        assertThat(assembly.report().promptLeakLinesRemoved()).isPositive();
    }
}
