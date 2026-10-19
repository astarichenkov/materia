package com.lectureprocessor.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.text.PromptTemplates;

class ScenarioCatalogTest {

    private final ScenarioCatalog catalog = new ScenarioCatalog();
    private final PromptTemplates prompts = new PromptTemplates();

    @Test
    void idsAreStableUniqueAndComplete() {
        Set<String> ids = new HashSet<>();
        for (ProcessingScenario scenario : catalog.all()) {
            assertThat(ids.add(scenario.id())).as("duplicate id " + scenario.id()).isTrue();
            assertThat(scenario.title()).isNotBlank();
            assertThat(scenario.description()).isNotBlank();
            assertThat(scenario.outputHint()).isNotBlank();
            assertThat(scenario.version()).isEqualTo(1);
        }
        assertThat(ids).contains("notes", "short-summary", "recipe", "dialogue", "translation",
                "instruction", "verbatim", "article", "faq", "meeting-protocol",
                "methodical-guide", "clean-transcript");
    }

    @Test
    void everyLlmScenarioHasAnExistingPromptAndFormat() {
        for (ProcessingScenario scenario : catalog.all()) {
            assertThat(scenario.recommendedFormat()).isNotNull();
            if (scenario.usesLlm()) {
                assertThat(scenario.recommendedPrompt()).isNotBlank();
                assertThat(prompts.exists(scenario.recommendedPrompt()))
                        .as("prompt of " + scenario.id()).isTrue();
                assertThat(scenario.assemblyPolicy()).isNotNull();
            } else {
                assertThat(scenario.recommendedFormat().usesLlm()).isFalse();
            }
        }
    }

    @Test
    void verbatimAndTranslationDoNotUseLlm() {
        assertThat(catalog.require("verbatim").usesLlm()).isFalse();
        assertThat(catalog.require("translation").usesLlm()).isFalse();
        assertThat(catalog.require("recipe").usesLlm()).isTrue();
    }

    @Test
    void unknownScenarioIsRejected() {
        assertThatThrownBy(() -> catalog.require("unknown-scenario"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Неизвестный сценарий");
        assertThat(ProcessingScenario.fromId(null)).isNull();
        assertThat(ProcessingScenario.fromId("RECIPE")).isEqualTo(ProcessingScenario.RECIPE);
    }
}
