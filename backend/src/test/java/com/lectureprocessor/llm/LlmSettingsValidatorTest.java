package com.lectureprocessor.llm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LlmSettingsValidatorTest {

    private static LlmRunConfig valid() {
        return new LlmRunConfig("ollama", "qwen3:1.7b", 0.1, 1536, 4096, false, "structured-notes-v1");
    }

    @Test
    void acceptsValidConfiguration() {
        assertThatCode(() -> LlmSettingsValidator.validate(valid())).doesNotThrowAnyException();
    }

    @Test
    void rejectsOutOfRangeTemperature() {
        assertThatThrownBy(() -> LlmSettingsValidator.validate(new LlmRunConfig(
                "ollama", "m", 2.5, 1536, 4096, false, "p")))
                .isInstanceOf(InvalidLlmSettingsException.class)
                .hasMessageContaining("temperature");
    }

    @Test
    void rejectsOutOfRangeMaxTokens() {
        assertThatThrownBy(() -> LlmSettingsValidator.validate(new LlmRunConfig(
                "ollama", "m", 0.1, 9000, 16384, false, "p")))
                .isInstanceOf(InvalidLlmSettingsException.class)
                .hasMessageContaining("max tokens");
    }

    @Test
    void rejectsOutOfRangeContext() {
        assertThatThrownBy(() -> LlmSettingsValidator.validate(new LlmRunConfig(
                "ollama", "m", 0.1, 512, 512, false, "p")))
                .isInstanceOf(InvalidLlmSettingsException.class)
                .hasMessageContaining("context");
    }

    @Test
    void rejectsContextNotLargerThanMaxTokens() {
        assertThatThrownBy(() -> LlmSettingsValidator.validate(new LlmRunConfig(
                "ollama", "m", 0.1, 4096, 2048, false, "p")))
                .isInstanceOf(InvalidLlmSettingsException.class)
                .hasMessageContaining("больше");
    }

    @Test
    void rejectsBlankFields() {
        assertThatThrownBy(() -> LlmSettingsValidator.validate(new LlmRunConfig(
                "", "m", 0.1, 100, 1024, false, "p")))
                .isInstanceOf(InvalidLlmSettingsException.class);
        assertThatThrownBy(() -> LlmSettingsValidator.validate(new LlmRunConfig(
                "ollama", "", 0.1, 100, 1024, false, "p")))
                .isInstanceOf(InvalidLlmSettingsException.class);
        assertThatThrownBy(() -> LlmSettingsValidator.validate(new LlmRunConfig(
                "ollama", "m", 0.1, 100, 1024, false, "")))
                .isInstanceOf(InvalidLlmSettingsException.class);
    }
}
