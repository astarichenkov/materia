package com.lectureprocessor.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.llm.LlmRunConfig;

class BenchmarkPresetServiceTest {

    private final BenchmarkPresetService presets = new BenchmarkPresetService();

    @Test
    void exposesBaselineOptimizedAndCustom() {
        assertThat(presets.presets()).extracting(BenchmarkPreset::id)
                .containsExactly("baseline", "optimized", "custom");
    }

    @Test
    void baselineAndOptimizedMatchVerifiedDay29Values() {
        LlmRunConfig baseline = presets.byId("baseline");
        assertThat(baseline.model()).isEqualTo("qwen3:4b");
        assertThat(baseline.temperature()).isEqualTo(0.1);
        assertThat(baseline.contextWindow()).isEqualTo(8192);
        assertThat(baseline.maxTokens()).isEqualTo(6144);
        assertThat(baseline.thinking()).isTrue();

        LlmRunConfig optimized = presets.byId("optimized");
        assertThat(optimized.model()).isEqualTo("qwen3:1.7b");
        assertThat(optimized.temperature()).isEqualTo(0.1);
        assertThat(optimized.contextWindow()).isEqualTo(4096);
        assertThat(optimized.maxTokens()).isEqualTo(1536);
        assertThat(optimized.thinking()).isFalse();
    }

    @Test
    void customHasNoOverrides() {
        assertThat(presets.byId("custom")).isNull();
    }

    @Test
    void optimizedPresetPassesValidation() {
        com.lectureprocessor.llm.LlmSettingsValidator.validate(presets.byId("optimized"));
        com.lectureprocessor.llm.LlmSettingsValidator.validate(presets.byId("baseline"));
    }

    @Test
    void promptsCatalogContainsRequiredTemplates() {
        List<com.lectureprocessor.text.PromptTemplates.Template> templates =
                new com.lectureprocessor.text.PromptTemplates().list();
        assertThat(templates).extracting(com.lectureprocessor.text.PromptTemplates.Template::name)
                .contains("basic-summary-v1", "clean-transcript-v1", "structured-notes-v1",
                        "structured-notes-v2");
    }
}
