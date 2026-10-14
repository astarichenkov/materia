package com.lectureprocessor.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class QualityEvaluatorTest {

    private final QualityEvaluator evaluator = new QualityEvaluator();

    private final QualitySpec spec = new QualitySpec(
            List.of("вектор", "скалярн", "перпендикуляр"),
            List.of("редактор субтитров"),
            List.of("0"));

    @Test
    void rewardsCompleteFactRichOutput() {
        String output = "## Векторы\n\n- вектор — направленный отрезок\n- скалярное произведение\n"
                + "- перпендикулярные векторы равны 0\n";
        QualityResult result = evaluator.evaluate(output, spec);
        assertThat(result.score()).isGreaterThanOrEqualTo(4.0);
        assertThat(result.notesText()).contains("facts=3/3");
    }

    @Test
    void penalisesMissingFacts() {
        QualityResult result = evaluator.evaluate("## Раздел\n\nНемного текста без ключевых слов.", spec);
        assertThat(result.score()).isLessThan(4.0);
    }

    @Test
    void flagsForbiddenHallucination() {
        QualityResult result = evaluator.evaluate(
                "## Раздел\n\nРедактор субтитров создал текст про вектор и скалярное произведение, перпендикуляр.",
                spec);
        assertThat(result.notesText()).contains("hallucination");
        assertThat(result.score()).isLessThan(5.0);
    }

    @Test
    void emptyOutputScoresZero() {
        assertThat(evaluator.evaluate("   ", spec).score()).isZero();
    }

    @Test
    void emptySpecDoesNotDivideByZero() {
        QualityResult result = evaluator.evaluate("## Раздел\n\n- пункт один\n- пункт два", QualitySpec.empty());
        assertThat(result.score()).isPositive();
    }
}
