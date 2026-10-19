package com.lectureprocessor.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.processing.DetailLevel;
import com.lectureprocessor.processing.MaterialType;
import com.lectureprocessor.processing.OutputFormat;
import com.lectureprocessor.processing.ProcessingProfile;

class ScenarioResolverTest {

    private final ScenarioCatalog catalog = new ScenarioCatalog();
    private final ScenarioResolver resolver = new ScenarioResolver(catalog);

    @Test
    void scenarioSuppliesRecommendedFormatPromptAndDetail() {
        ProcessingProfile profile = resolver.resolve("recipe", null, null, null, null);

        assertThat(profile.scenario()).isEqualTo(ProcessingScenario.RECIPE);
        assertThat(profile.outputFormat()).isEqualTo(OutputFormat.RECIPE);
        assertThat(profile.resolvedPrompt()).isEqualTo("scenario-recipe-v1");
        assertThat(profile.detailLevel()).isEqualTo(DetailLevel.DETAILED);
        assertThat(profile.materialType()).isEqualTo(MaterialType.AUTO);
        assertThat(profile.explicit()).isTrue();
    }

    @Test
    void verbatimScenarioHasNoLlmPromptAndIgnoresPromptOverride() {
        ProcessingProfile profile = resolver.resolve("verbatim", null, null, null, null);
        assertThat(profile.resolvedPrompt()).isNull();
        assertThat(profile.usesLlm()).isFalse();
        assertThat(profile.documentLabel()).isEqualTo("Транскрипция");

        // A template is remembered but never applied: compatibility reports INFO,
        // not a silent substitution.
        ProcessingProfile withPrompt = resolver.resolve("verbatim", null, null, null,
                "scenario-notes-v1");
        assertThat(withPrompt.promptTemplate()).isEqualTo("scenario-notes-v1");
        assertThat(withPrompt.usesLlm()).isFalse();
    }

    @Test
    void manualOverridesWinOverRecommendations() {
        ProcessingProfile profile = resolver.resolve("recipe", "PRACTICAL_TUTORIAL",
                "STEP_BY_STEP", "BRIEF", "notes-howto-v1");

        assertThat(profile.materialType()).isEqualTo(MaterialType.PRACTICAL_TUTORIAL);
        assertThat(profile.outputFormat()).isEqualTo(OutputFormat.STEP_BY_STEP);
        assertThat(profile.detailLevel()).isEqualTo(DetailLevel.BRIEF);
        assertThat(profile.resolvedPrompt()).isEqualTo("notes-howto-v1");
        assertThat(profile.scenario()).isEqualTo(ProcessingScenario.RECIPE);
    }

    @Test
    void legacyStyleResolveWithoutScenarioKeepsRequestedFormat() {
        ProcessingProfile profile = resolver.resolve(null, "INTERVIEW", "INTERVIEW_QA",
                "STANDARD", null);
        assertThat(profile.scenario()).isNull();
        assertThat(profile.resolvedPrompt()).isEqualTo("notes-interview-v1");
    }

    @Test
    void invalidScenarioFailsFast() {
        assertThatThrownBy(() -> resolver.resolve("not-a-scenario", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Неизвестный сценарий");
    }

    @Test
    void fingerprintIncludesScenarioAndVersion() {
        ProcessingProfile recipe = resolver.resolve("recipe", null, null, null, null);
        ProcessingProfile summary = resolver.resolve("short-summary", null, null, null, null);
        assertThat(recipe.fingerprintPart()).contains("recipe@1");
        assertThat(summary.fingerprintPart()).contains("short-summary@1");
        assertThat(recipe.fingerprintPart()).isNotEqualTo(summary.fingerprintPart());
    }
}
