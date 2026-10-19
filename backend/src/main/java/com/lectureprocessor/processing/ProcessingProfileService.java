package com.lectureprocessor.processing;

import java.util.List;

import org.springframework.stereotype.Service;

import com.lectureprocessor.llm.LlmSettingsService;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.text.PromptTemplates;
import com.lectureprocessor.scenario.ProcessingScenario;
import com.lectureprocessor.scenario.ScenarioCatalog;
import com.lectureprocessor.scenario.ScenarioResolver;

/**
 * Resolves the effective output profile for a lecture. When the user has never
 * chosen a profile the configured default prompt is preserved (legacy
 * behaviour), so old lectures never change on a re-run by accident.
 */
@Service
public class ProcessingProfileService {

    private final ProcessingProfileRepository repository;
    private final LlmSettingsService llmSettings;
    private final PromptTemplates prompts;
    private final ScenarioCatalog scenarios;
    private final ScenarioResolver resolver;

    public ProcessingProfileService(ProcessingProfileRepository repository,
            LlmSettingsService llmSettings, PromptTemplates prompts, ScenarioCatalog scenarios,
            ScenarioResolver resolver) {
        this.repository = repository;
        this.llmSettings = llmSettings;
        this.prompts = prompts;
        this.scenarios = scenarios;
        this.resolver = resolver;
    }

    public ProcessingProfile get(String lectureId) {
        return repository.find(lectureId).orElseGet(this::legacyDefault);
    }

    public boolean hasExplicitProfile(String lectureId) {
        return repository.find(lectureId).isPresent();
    }

    private ProcessingProfile legacyDefault() {
        LlmRunConfig config = llmSettings.current();
        String prompt = config.promptTemplate() == null
                ? PromptTemplates.STRUCTURED_NOTES : config.promptTemplate();
        return ProcessingProfile.legacy(prompt);
    }

    /**
     * Validates and persists a user selection. A missing prompt means "use the
     * recommended prompt of the selected format"; the explicit template must
     * exist in the shipped catalog.
     */
    public ProcessingProfile save(String lectureId, String materialType, String outputFormat,
            String detailLevel, String promptTemplate) {
        return save(lectureId, materialType, outputFormat, detailLevel, promptTemplate, null);
    }

    /**
     * Persists a scenario selection. Blank overrides fall back to the scenario
     * recommendation; an explicitly selected template must exist.
     */
    public ProcessingProfile save(String lectureId, String materialType, String outputFormat,
            String detailLevel, String promptTemplate, String scenarioId) {
        ProcessingProfile profile = resolver.resolve(scenarioId, materialType, outputFormat,
                detailLevel, promptTemplate);
        validateTemplate(profile.promptTemplate());
        repository.save(lectureId, profile);
        return profile;
    }

    public ProcessingProfile preview(String materialType, String outputFormat, String detailLevel,
            String promptTemplate) {
        return preview(materialType, outputFormat, detailLevel, promptTemplate, null);
    }

    public ProcessingProfile preview(String materialType, String outputFormat, String detailLevel,
            String promptTemplate, String scenarioId) {
        ProcessingProfile profile = resolver.resolve(scenarioId, materialType, outputFormat,
                detailLevel, promptTemplate);
        validateTemplate(profile.promptTemplate());
        return profile;
    }

    private void validateTemplate(String template) {
        if (template != null && !template.isBlank() && !prompts.exists(template)) {
            throw new IllegalArgumentException("Неизвестный шаблон промпта: " + template);
        }
    }

    public List<ProcessingScenario> scenarios() {
        return scenarios.all();
    }

    public List<MaterialType> materialTypes() {
        return List.of(MaterialType.values());
    }

    public List<OutputFormat> outputFormats() {
        return List.of(OutputFormat.values());
    }

    public List<DetailLevel> detailLevels() {
        return DetailLevel.all();
    }
}
