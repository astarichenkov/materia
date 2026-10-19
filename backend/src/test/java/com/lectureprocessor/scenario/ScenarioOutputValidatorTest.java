package com.lectureprocessor.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.text.ProcessingException;

class ScenarioOutputValidatorTest {

    private final ScenarioOutputValidator validator = new ScenarioOutputValidator();

    private final List<TranscriptSegment> recipeSegments = List.of(
            new TranscriptSegment(0, 0, 1000, "Возьмём 200 граммов муки и 2 яйца."),
            new TranscriptSegment(1, 1000, 2000, "Добавим 300 миллилитров молока и щепотку соли."),
            new TranscriptSegment(2, 2000, 3000, "Жарим по 2 минуты с каждой стороны."));

    @Test
    void emptyResultIsAHardFailure() {
        assertThatThrownBy(() -> validator.validate(ProcessingScenario.RECIPE, "  ",
                recipeSegments)).isInstanceOf(ProcessingException.class);
    }

    @Test
    void recipeWithLostQuantitiesIsWarned() {
        String markdown = """
                ## Ингредиенты
                - мука — количество не указано
                - яйца — количество не указано

                ## Приготовление
                1. Смешать и жарить.
                """;

        List<String> warnings = validator.validate(ProcessingScenario.RECIPE, markdown,
                recipeSegments);

        assertThat(warnings).anyMatch(w -> w.contains("потеряна часть количеств"));
    }

    @Test
    void recipeKeepingQuantitiesHasNoNumberWarning() {
        String markdown = """
                ## Ингредиенты
                - мука — 200 граммов
                - яйца — 2
                - молоко — 300 миллилитров
                - соль — щепотка

                ## Приготовление
                1. Смешать.
                2. Жарить по 2 минуты с каждой стороны.
                """;

        List<String> warnings = validator.validate(ProcessingScenario.RECIPE, markdown,
                recipeSegments);

        assertThat(warnings).noneMatch(w -> w.contains("потеряна часть количеств"));
    }

    @Test
    void summaryWithInventedAcademicSectionIsWarned() {
        String markdown = """
                ## Краткое содержание
                Герой проходит путь.

                ## Контрольные вопросы
                1. Какой путь прошёл герой?
                """;

        List<String> warnings = validator.validate(ProcessingScenario.SHORT_SUMMARY, markdown,
                List.of(new TranscriptSegment(0, 0, 1000, "Герой проходит путь.")));

        assertThat(warnings).anyMatch(w -> w.contains("академический раздел"));
    }

    @Test
    void instructionWithoutStepsIsWarned() {
        String markdown = "## Цель\nПросто рассказ без действий.";
        List<String> warnings = validator.validate(ProcessingScenario.INSTRUCTION, markdown,
                List.of(new TranscriptSegment(0, 0, 1000, "Сегодня поговорим о жизни.")));
        assertThat(warnings).anyMatch(w -> w.contains("последовательности шагов"));
    }

    @Test
    void promptEchoIsWarned() {
        String markdown = "## Раздел\nФормат результата: РЕЦЕПТ. Модель повторяет инструкцию.";
        List<String> warnings = validator.validate(ProcessingScenario.RECIPE, markdown,
                recipeSegments);
        assertThat(warnings).anyMatch(w -> w.contains("эхо служебной инструкции"));
    }
}
