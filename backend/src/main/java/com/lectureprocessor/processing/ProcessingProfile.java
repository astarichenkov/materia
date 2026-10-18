package com.lectureprocessor.processing;

/**
 * Effective output profile of a lecture.
 *
 * @param materialType  source material kind
 * @param outputFormat  requested document format
 * @param detailLevel   requested detail level
 * @param promptTemplate resolved prompt template name; when {@code null} the
 *                       {@link OutputFormat#defaultPrompt()} is used
 * @param explicit      whether the user explicitly selected this profile (a row
 *                       exists) or it is the conservative default used for
 *                       material processed before profiles existed
 */
public record ProcessingProfile(
        MaterialType materialType,
        OutputFormat outputFormat,
        DetailLevel detailLevel,
        String promptTemplate,
        boolean explicit) {

    /** Prompt actually sent to the LLM. */
    public String resolvedPrompt() {
        if (promptTemplate != null && !promptTemplate.isBlank()) {
            return promptTemplate;
        }
        return outputFormat.defaultPrompt();
    }

    /** Everything that must invalidate text processing when it changes. */
    public String fingerprintPart() {
        return "profile:" + materialType.name() + ":" + outputFormat.name() + ":"
                + detailLevel.name() + ":" + resolvedPrompt();
    }

    /** Extra instruction appended to the user message (explicit profiles only). */
    public String instructions() {
        if (!explicit) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        text.append("Тип исходного материала: ").append(materialType.label()).append(". ")
                .append(materialType.description());
        text.append("\n").append(detailLevel.instruction());
        return text.toString();
    }

    public static ProcessingProfile explicit(MaterialType materialType, OutputFormat outputFormat,
            DetailLevel detailLevel, String promptTemplate) {
        return new ProcessingProfile(materialType, outputFormat, detailLevel,
                promptTemplate == null || promptTemplate.isBlank() ? null : promptTemplate.strip(),
                true);
    }

    /**
     * Conservative default: keeps the prompt configured in the application
     * settings so re-running an old lecture does not silently change its result.
     */
    public static ProcessingProfile legacy(String configuredPrompt) {
        return new ProcessingProfile(MaterialType.AUTO, OutputFormat.DETAILED_NOTES,
                DetailLevel.STANDARD, configuredPrompt, false);
    }

    public ProcessingProfile withPrompt(String template) {
        return new ProcessingProfile(materialType, outputFormat, detailLevel, template, explicit);
    }
}
