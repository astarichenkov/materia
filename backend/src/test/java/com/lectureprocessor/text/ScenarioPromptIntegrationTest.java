package com.lectureprocessor.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.llm.LlmOptions;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.TestProperties;

/**
 * The scenario decides the prompt contract, the assembly policy, the document
 * title and whether an extra summary call is useful. These are the behaviours
 * that prevent a recipe from becoming an academic lecture summary.
 */
class ScenarioPromptIntegrationTest {

    private final FakeLlmProvider llm = new FakeLlmProvider();
    private final TextProcessingService service = new TextProcessingService(llm,
            new TextChunker(), new PromptTemplates(), new NotesAssembler(),
            TestProperties.defaults("./target/test-data"));

    private final List<TranscriptSegment> segments = List.of(
            new TranscriptSegment(0, 0, 1000, "Возьмём 200 граммов муки и 2 яйца."),
            new TranscriptSegment(1, 1000, 2000, "Смешаем и жарим 3 минуты."));

    @Test
    void recipeUsesRecipeTemplateContractAndNoSummaryCall() {
        List<String> systems = new ArrayList<>();
        llm.setChatFunction((system, user) -> {
            systems.add(system);
            return "## Ингредиенты\n- мука — 200 граммов\n- яйца — 2\n\n## Приготовление\n1. Жарить 3 минуты.";
        });

        TextProcessingService.GeneratedNotes generated = service.generateStructuredNotes(llm,
                segments, LlmOptions.of("fake-model"), PromptTemplates.SCENARIO_RECIPE,
                "Сценарий результата: Рецепт.", "Формат результата: РЕЦЕПТ.",
                AssemblyPolicy.STRUCTURED, "Рецепт", false, ChunkProgressListener.NONE);

        assertThat(generated.notes().markdown()).startsWith("# Рецепт");
        assertThat(generated.notes().markdown()).doesNotContain("## Резюме");
        assertThat(systems.getFirst())
                .contains("кулинарных рецептов")
                .contains("Формат результата: РЕЦЕПТ")
                .contains("Сценарий результата: Рецепт");
        // One LLM call per chunk and no separate summary call.
        assertThat(llm.chatCalls()).hasSize(1);
    }

    @Test
    void dialogueAssemblyKeepsRepeatedReplies() {
        llm.setChatFunction((system, user) -> "## Диалог\n— Привет!\n— Привет!");
        TextProcessingService.GeneratedNotes generated = service.generateStructuredNotes(llm,
                segments, LlmOptions.of("fake-model"), PromptTemplates.SCENARIO_DIALOGUE,
                null, "Формат результата: ДИАЛОГ.", AssemblyPolicy.DIALOGUE, "Диалоги",
                false, ChunkProgressListener.NONE);

        assertThat(generated.notes().markdown()).startsWith("# Диалоги");
        assertThat(generated.notes().markdown().split("Привет!", -1).length).isEqualTo(3);
        assertThat(generated.quality().duplicateBlocksRemoved()).isZero();
    }

    @Test
    void transcriptIsTheOnlyUserMessageContent() {
        List<String> users = new ArrayList<>();
        llm.setChatFunction((system, user) -> {
            users.add(user);
            return "## Итог\nТекст.";
        });
        service.generateStructuredNotes(llm, segments, LlmOptions.of("fake-model"),
                PromptTemplates.SCENARIO_SUMMARY, "Сценарий результата: Краткий пересказ.",
                "Формат результата: КРАТКИЙ ПЕРЕСКАЗ.", AssemblyPolicy.CHRONOLOGICAL,
                "Краткий пересказ", false, ChunkProgressListener.NONE);

        assertThat(users).isNotEmpty();
        assertThat(users.getFirst()).contains("<transcript>").contains("200 граммов муки")
                .doesNotContain("Сценарий результата")
                .doesNotContain("Формат результата");
    }
}
