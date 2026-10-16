package com.lectureprocessor.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class SupportAnswerValidatorTest {

    private final SupportAnswerValidator validator = new SupportAnswerValidator(new ObjectMapper());

    @Test
    void parsesJsonEnvelope() {
        var result = validator.validate("""
                {"answer":"Транскрипция завершена, упал TEXT_PROCESSING.",
                 "suggestedActions":["Повторить этап форматирования"],
                 "limitations":["Нет логов провайдера"]}
                """, true);
        assertThat(result.answer()).contains("TEXT_PROCESSING");
        assertThat(result.suggestedActions()).containsExactly("Повторить этап форматирования");
        assertThat(result.limitations()).containsExactly("Нет логов провайдера");
    }

    @Test
    void toleratesMarkdownFencesAndPlainProse() {
        var fenced = validator.validate("```json\n{\"answer\":\"ok\"}\n```", true);
        assertThat(fenced.answer()).isEqualTo("ok");
        var prose = validator.validate("Просто текст ответа", true);
        assertThat(prose.answer()).isEqualTo("Просто текст ответа");
    }

    @Test
    void addsLimitationWhenNotGrounded() {
        var result = validator.validate("{\"answer\":\"возможно, упал провайдер\"}", false);
        assertThat(result.limitations()).isNotEmpty();
        assertThat(result.limitations().getFirst()).contains("данн");
    }

    @Test
    void redactsSecretsAndBounds() {
        var result = validator.validate("{\"answer\":\"ключ api_key=abcdef1234567890 утёк\"}", true);
        assertThat(result.answer()).doesNotContain("abcdef1234567890");
        assertThat(result.answer()).contains("[REDACTED]");
    }

    @Test
    void emptyAnswerIsRejected() {
        assertThatThrownBy(() -> validator.validate("  ", true))
                .isInstanceOf(SupportUnavailableException.class);
    }
}
