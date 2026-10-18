package com.lectureprocessor.processing;

import java.util.List;

import org.springframework.stereotype.Service;

import com.lectureprocessor.llm.LlmSettingsService;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.text.PromptTemplates;

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

    public ProcessingProfileService(ProcessingProfileRepository repository,
            LlmSettingsService llmSettings, PromptTemplates prompts) {
        this.repository = repository;
        this.llmSettings = llmSettings;
        this.prompts = prompts;
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
        OutputFormat format = OutputFormat.from(outputFormat);
        String template = promptTemplate == null || promptTemplate.isBlank()
                ? null : promptTemplate.strip();
        if (template != null && !prompts.exists(template)) {
            throw new IllegalArgumentException("Неизвестный шаблон промпта: " + template);
        }
        ProcessingProfile profile = ProcessingProfile.explicit(MaterialType.from(materialType),
                format, DetailLevel.from(detailLevel), template);
        repository.save(lectureId, profile);
        return profile;
    }

    public ProcessingProfile preview(String materialType, String outputFormat, String detailLevel,
            String promptTemplate) {
        String template = promptTemplate == null || promptTemplate.isBlank()
                ? null : promptTemplate.strip();
        return ProcessingProfile.explicit(MaterialType.from(materialType),
                OutputFormat.from(outputFormat), DetailLevel.from(detailLevel), template);
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
