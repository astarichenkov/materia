package com.lectureprocessor.llm;

import java.util.Locale;

/**
 * Logical LLM consumers. Model assignments are isolated per task: changing the
 * model for one task never changes another.
 */
public enum LlmTaskType {

    /** Lecture cleaning/notes processing (the Day 27–29 pipeline). */
    LECTURE_PROCESSING("lecture-processing", "Lecture Processing"),

    /** Project developer assistant ({@code /help}, docs RAG). */
    DEVELOPER_ASSISTANT("developer-assistant", "Developer Assistant"),

    /** Automated AI code review (Day 32 preparation). */
    CODE_REVIEW("code-review", "Code Review"),

    /** User-facing support assistant (Day 33). */
    SUPPORT_ASSISTANT("support-assistant", "Support Assistant"),

    /** Autonomous file agent (Day 34). */
    FILE_AGENT("file-agent", "File Agent");

    private final String id;
    private final String label;

    LlmTaskType(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static LlmTaskType from(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidLlmSettingsException("Не задан тип LLM-задачи");
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        for (LlmTaskType type : values()) {
            if (type.id.equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        throw new InvalidLlmSettingsException("Неизвестный тип LLM-задачи: " + value);
    }
}
