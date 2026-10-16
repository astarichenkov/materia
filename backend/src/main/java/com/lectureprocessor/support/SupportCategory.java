package com.lectureprocessor.support;

import java.util.Locale;

/** Category of a support ticket, used for routing hints and filtering. */
public enum SupportCategory {

    GENERAL("Общий вопрос"),
    DOWNLOAD("Скачивание"),
    TRANSCRIPTION("Распознавание"),
    LLM("LLM / обработка текста"),
    SLIDES("Слайды"),
    EXPORT("Экспорт документов"),
    CONFIGURATION("Настройка"),
    OTHER("Другое");

    private final String label;

    SupportCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Parses a category case-insensitively; {@code null}/blank maps to GENERAL. */
    public static SupportCategory from(String value) {
        if (value == null || value.isBlank()) {
            return GENERAL;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }

    public static SupportCategory fromOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
