package com.lectureprocessor.processing;

import java.util.Locale;

/**
 * Requested document format. Each format resolves to one versioned prompt
 * template; the reliability rules live in the prompt, not in copied Java code.
 * Values are persisted by name and must stay stable.
 */
public enum OutputFormat {
    DETAILED_NOTES("Подробный конспект",
            "Развёрнутый конспект с определениями, аргументами и выводами.",
            "notes-academic-v1", false),
    BRIEF_NOTES("Краткий конспект",
            "Главные идеи и ключевые выводы в компактной структуре.",
            "notes-brief-v1", false),
    STEP_BY_STEP("Пошаговая инструкция",
            "Последовательность действий с командами и ожидаемым результатом.",
            "notes-howto-v1", false),
    CLEAN_TRANSCRIPT("Очищенная транскрипция",
            "Близкий к оригиналу текст с исправленной пунктуацией и абзацами.",
            "clean-transcript-v2", true),
    METHODICAL_GUIDE("Методическое пособие",
            "Учебные цели, порядок изучения, задания и контрольные вопросы.",
            "notes-methodical-v1", false),
    ARTICLE("Статья",
            "Связный текст с введением, разделами и заключением.",
            "notes-article-v1", false),
    FAQ("FAQ",
            "Частые вопросы и ответы, основанные только на материале.",
            "notes-faq-v1", false),
    MEETING_PROTOCOL("Протокол встречи",
            "Повестка, решения, задачи, ответственные и сроки из материала.",
            "notes-meeting-v1", false),
    INTERVIEW_QA("Вопросы и ответы",
            "Вопросы ведущего и ответы участников без выдуманных реплик.",
            "notes-interview-v1", false);

    private final String label;
    private final String description;
    private final String defaultPrompt;
    private final boolean cleanTranscript;

    OutputFormat(String label, String description, String defaultPrompt,
            boolean cleanTranscript) {
        this.label = label;
        this.description = description;
        this.defaultPrompt = defaultPrompt;
        this.cleanTranscript = cleanTranscript;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public String defaultPrompt() {
        return defaultPrompt;
    }

    /** True when the deliverable is the cleaned transcript itself. */
    public boolean cleanTranscript() {
        return cleanTranscript;
    }

    public static OutputFormat from(String value) {
        if (value == null || value.isBlank()) {
            return DETAILED_NOTES;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DETAILED_NOTES;
        }
    }
}
