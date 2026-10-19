package com.lectureprocessor.processing;

import com.lectureprocessor.scenario.ProcessingScenario;

/**
 * Effective output profile of a material.
 *
 * @param materialType  source material kind
 * @param outputFormat  requested document format
 * @param detailLevel   requested detail level
 * @param promptTemplate resolved prompt template name; when {@code null} the
 *                       scenario recommendation or the
 *                       {@link OutputFormat#defaultPrompt()} is used
 * @param explicit      whether the user explicitly selected this profile (a row
 *                       exists) or it is the conservative default used for
 *                       material processed before profiles existed
 * @param scenario      requested processing scenario; {@code null} for profiles
 *                       created before Materia scenarios and for legacy rows
 */
public record ProcessingProfile(
        MaterialType materialType,
        OutputFormat outputFormat,
        DetailLevel detailLevel,
        String promptTemplate,
        boolean explicit,
        ProcessingScenario scenario) {

    /** Prompt actually sent to the LLM. May be {@code null} for no-LLM scenarios. */
    public String resolvedPrompt() {
        if (promptTemplate != null && !promptTemplate.isBlank()) {
            return promptTemplate;
        }
        if (scenario != null && scenario.usesLlm() && scenario.recommendedPrompt() != null) {
            return scenario.recommendedPrompt();
        }
        return outputFormat.defaultPrompt();
    }

    /** Everything that must invalidate text processing when it changes. */
    public String fingerprintPart() {
        return "profile:" + materialType.name() + ":" + outputFormat.name() + ":"
                + detailLevel.name() + ":" + resolvedPrompt() + ":"
                + (scenario == null ? "legacy" : scenario.id() + "@" + scenario.version());
    }

    /** Extra instruction appended to the system prompt (explicit profiles only). */
    public String instructions() {
        if (!explicit) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        text.append("Тип исходного материала: ").append(materialType.label()).append(". ")
                .append(materialType.description());
        if (scenario != null && scenario.usesLlm()) {
            text.append("\nСценарий результата: ").append(scenario.title()).append(". ")
                    .append(scenario.outputHint());
        }
        text.append("\n").append(detailLevel.instruction());
        return text.toString();
    }

    /** Human label of the produced document, e.g. «Рецепт» или «Конспект». */
    public String documentLabel() {
        return scenario != null ? scenario.documentLabel() : outputFormat.documentLabel();
    }

    public boolean usesLlm() {
        return scenario == null || scenario.usesLlm();
    }

    public static ProcessingProfile explicit(MaterialType materialType, OutputFormat outputFormat,
            DetailLevel detailLevel, String promptTemplate) {
        return new ProcessingProfile(materialType, outputFormat, detailLevel,
                promptTemplate == null || promptTemplate.isBlank() ? null : promptTemplate.strip(),
                true, null);
    }

    public static ProcessingProfile explicit(MaterialType materialType, OutputFormat outputFormat,
            DetailLevel detailLevel, String promptTemplate, ProcessingScenario scenario) {
        return new ProcessingProfile(materialType, outputFormat, detailLevel,
                promptTemplate == null || promptTemplate.isBlank() ? null : promptTemplate.strip(),
                true, scenario);
    }

    /**
     * Conservative default: keeps the prompt configured in the application
     * settings so re-running old material does not silently change its result.
     */
    public static ProcessingProfile legacy(String configuredPrompt) {
        return new ProcessingProfile(MaterialType.AUTO, OutputFormat.DETAILED_NOTES,
                DetailLevel.STANDARD, configuredPrompt, false, null);
    }

    public ProcessingProfile withPrompt(String template) {
        return new ProcessingProfile(materialType, outputFormat, detailLevel, template, explicit,
                scenario);
    }

    public ProcessingProfile withScenario(ProcessingScenario value) {
        return new ProcessingProfile(materialType, outputFormat, detailLevel, promptTemplate,
                explicit, value);
    }
}
