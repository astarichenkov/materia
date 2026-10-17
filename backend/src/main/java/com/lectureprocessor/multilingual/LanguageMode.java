package com.lectureprocessor.multilingual;

import java.util.Locale;

/**
 * How the source language is chosen. {@link #AUTO} lets Whisper detect the
 * language; the explicit modes force a language for accurate recognition and
 * act as a manual override of detection.
 */
public enum LanguageMode {
    AUTO("auto", "Авто-определение"),
    ENGLISH("en", "Английский"),
    RUSSIAN("ru", "Русский");

    private final String whisperCode;
    private final String label;

    LanguageMode(String whisperCode, String label) {
        this.whisperCode = whisperCode;
        this.label = label;
    }

    /** Language code passed to whisper.cpp ({@code auto} enables detection). */
    public String whisperCode() {
        return whisperCode;
    }

    public String label() {
        return label;
    }

    public boolean explicit() {
        return this != AUTO;
    }

    public static LanguageMode from(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (LanguageMode mode : values()) {
            if (mode.name().toLowerCase(Locale.ROOT).equals(normalized)
                    || mode.whisperCode.equals(normalized)) {
                return mode;
            }
        }
        return AUTO;
    }
}
