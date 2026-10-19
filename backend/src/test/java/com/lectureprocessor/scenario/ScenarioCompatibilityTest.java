package com.lectureprocessor.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.processing.MaterialType;
import com.lectureprocessor.processing.OutputFormat;
import com.lectureprocessor.processing.ProcessingProfile;
import com.lectureprocessor.processing.TemplateCompatibility;
import com.lectureprocessor.processing.TemplateCompatibility.Severity;
import com.lectureprocessor.text.PromptTemplates;

class ScenarioCompatibilityTest {

    private final PromptTemplates prompts = new PromptTemplates();
    private final TemplateCompatibility legacy = new TemplateCompatibility(prompts);
    private final ScenarioCompatibility compatibility = new ScenarioCompatibility(prompts, legacy);

    @Test
    void recipeWithNotesBriefTemplateIsAWarningWithRecommendation() {
        ProcessingProfile profile = ProcessingProfile.explicit(MaterialType.AUTO,
                OutputFormat.RECIPE, com.lectureprocessor.processing.DetailLevel.STANDARD,
                PromptTemplates.NOTES_BRIEF, ProcessingScenario.RECIPE);

        List<TemplateCompatibility.Issue> issues = compatibility.evaluate(profile);

        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.severity()).isEqualTo(Severity.WARNING);
            assertThat(issue.message()).contains("scenario-recipe-v1");
            assertThat(issue.recommendedTemplate()).isEqualTo("scenario-recipe-v1");
        });
        assertThat(compatibility.hasIncompatible(issues)).isFalse();
    }

    @Test
    void noLlmScenarioWithLlmFormatIsIncompatible() {
        ProcessingProfile profile = ProcessingProfile.explicit(MaterialType.AUTO,
                OutputFormat.DETAILED_NOTES, com.lectureprocessor.processing.DetailLevel.STANDARD,
                null, ProcessingScenario.VERBATIM);

        List<TemplateCompatibility.Issue> issues = compatibility.evaluate(profile);

        assertThat(compatibility.hasIncompatible(issues)).isTrue();
        assertThat(compatibility.firstBlockingMessage(issues))
                .contains("не использует LLM").contains("Подробный конспект");
    }

    @Test
    void unknownTemplateFailsFast() {
        ProcessingProfile profile = ProcessingProfile.explicit(MaterialType.AUTO,
                OutputFormat.RECIPE, com.lectureprocessor.processing.DetailLevel.STANDARD,
                "missing-prompt", ProcessingScenario.RECIPE);

        assertThat(compatibility.evaluate(profile)).anySatisfy(issue -> {
            assertThat(issue.severity()).isEqualTo(Severity.INCOMPATIBLE);
            assertThat(issue.message()).contains("не найден");
        });
    }

    @Test
    void scenarioNotSupportingMaterialTypeIsOnlyAWarning() {
        ProcessingProfile profile = ProcessingProfile.explicit(MaterialType.INTERVIEW,
                OutputFormat.DETAILED_NOTES,
                com.lectureprocessor.processing.DetailLevel.STANDARD,
                PromptTemplates.NOTES_ACADEMIC, ProcessingScenario.ACADEMIC_NOTES);

        List<TemplateCompatibility.Issue> issues = compatibility.evaluate(profile);

        assertThat(issues).anySatisfy(issue -> assertThat(issue.severity())
                .isEqualTo(Severity.WARNING));
        assertThat(compatibility.hasIncompatible(issues)).isFalse();
    }

    @Test
    void legacyProfileWithoutScenarioStillUsesTheOldMatrix() {
        ProcessingProfile profile = ProcessingProfile.explicit(MaterialType.AUTO,
                OutputFormat.CLEAN_TRANSCRIPT,
                com.lectureprocessor.processing.DetailLevel.STANDARD,
                PromptTemplates.NOTES_BRIEF, null);

        assertThat(compatibility.hasIncompatible(compatibility.evaluate(profile))).isTrue();
    }
}
