package com.lectureprocessor.scenario;

import org.springframework.stereotype.Service;

import com.lectureprocessor.processing.DetailLevel;
import com.lectureprocessor.processing.MaterialType;
import com.lectureprocessor.processing.OutputFormat;
import com.lectureprocessor.processing.ProcessingProfile;

/**
 * Turns a scenario selection (plus optional manual overrides) into an effective
 * {@link ProcessingProfile}. The scenario supplies the recommended format,
 * detail level and prompt; a blank field means "use the recommendation", never a
 * hidden replacement of a value the user chose.
 */
@Service
public class ScenarioResolver {

    private final ScenarioCatalog catalog;

    public ScenarioResolver(ScenarioCatalog catalog) {
        this.catalog = catalog;
    }

    public ProcessingProfile resolve(String scenarioId, String materialType, String outputFormat,
            String detailLevel, String promptTemplate) {
        ProcessingScenario scenario = scenarioId == null || scenarioId.isBlank()
                ? null : catalog.require(scenarioId);
        MaterialType material = (materialType == null || materialType.isBlank())
                ? MaterialType.AUTO : MaterialType.from(materialType);
        if (scenario == null) {
            OutputFormat format = (outputFormat == null || outputFormat.isBlank())
                    ? OutputFormat.DETAILED_NOTES : OutputFormat.from(outputFormat);
            DetailLevel detail = (detailLevel == null || detailLevel.isBlank())
                    ? DetailLevel.STANDARD : DetailLevel.from(detailLevel);
            return ProcessingProfile.explicit(material, format, detail, promptTemplate, null);
        }
        OutputFormat format = (outputFormat == null || outputFormat.isBlank())
                ? scenario.recommendedFormat() : OutputFormat.from(outputFormat);
        DetailLevel detail = (detailLevel == null || detailLevel.isBlank())
                ? scenario.recommendedDetail() : DetailLevel.from(detailLevel);
        String prompt;
        if (promptTemplate != null && !promptTemplate.isBlank()) {
            prompt = promptTemplate.strip();
        } else {
            prompt = scenario.usesLlm() ? scenario.recommendedPrompt() : null;
        }
        return ProcessingProfile.explicit(material, format, detail, prompt, scenario);
    }

    /** Profile for an existing material without changing its format selection. */
    public ProcessingProfile withScenario(ProcessingProfile base, String scenarioId) {
        ProcessingScenario scenario = catalog.require(scenarioId);
        ProcessingProfile resolved = resolve(scenario.id(), base.materialType().name(),
                base.outputFormat().name(), base.detailLevel().name(), base.promptTemplate());
        return resolved;
    }
}
