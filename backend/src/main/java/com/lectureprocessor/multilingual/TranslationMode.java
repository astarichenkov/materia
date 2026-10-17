package com.lectureprocessor.multilingual;

import java.util.Locale;

/**
 * Requested document set. Russian sources are never translated into Russian:
 * the resolved mode for a Russian lecture is {@link #ORIGINAL_ONLY}.
 */
public enum TranslationMode {
    ORIGINAL_ONLY("Только оригинал"),
    TRANSLATE_TO_RUSSIAN("Перевод на русский"),
    BILINGUAL("Двуязычный документ");

    private final String label;

    TranslationMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean wantsTranslation() {
        return this != ORIGINAL_ONLY;
    }

    public static TranslationMode from(String value) {
        if (value == null || value.isBlank()) {
            return TRANSLATE_TO_RUSSIAN;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (TranslationMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return TRANSLATE_TO_RUSSIAN;
    }
}
