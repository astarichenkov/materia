package com.lectureprocessor.editor;

/**
 * Editable documents. Each type has its own revision stream and its own
 * staleness state; editing one type never silently rewrites another.
 */
public enum DocumentType {
    /** Original timestamped transcript (Whisper output is the first revision). */
    TRANSCRIPT("Оригинал"),
    /** Russian translation per segment. */
    TRANSLATION("Перевод"),
    /** Structured Markdown notes. */
    NOTES("Конспект");

    private final String label;

    DocumentType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static DocumentType from(String value) {
        if (value == null || value.isBlank()) {
            throw new EditorValidationException("Не задан тип документа");
        }
        try {
            return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new EditorValidationException("Неизвестный тип документа: " + value);
        }
    }
}
