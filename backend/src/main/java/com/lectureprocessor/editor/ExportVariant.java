package com.lectureprocessor.editor;

/** Export variants that can be regenerated from saved revisions. */
public enum ExportVariant {
    /** Original-language transcript document. */
    ORIGINAL("notes.original.docx", "transcript.original.md"),
    /** Russian document built from the latest translation revision. */
    RUSSIAN("notes.ru.docx", "transcript.ru.md"),
    /** Original + translation per segment. */
    BILINGUAL("notes.bilingual.docx", "transcript.bilingual.md"),
    /** Structured notes with preserved slide appendix. */
    NOTES("notes.docx", "notes.md");

    private final String docxArtifact;
    private final String markdownArtifact;

    ExportVariant(String docxArtifact, String markdownArtifact) {
        this.docxArtifact = docxArtifact;
        this.markdownArtifact = markdownArtifact;
    }

    public String docxArtifact() {
        return docxArtifact;
    }

    public String markdownArtifact() {
        return markdownArtifact;
    }

    public static ExportVariant from(String value) {
        if (value == null || value.isBlank()) {
            throw new EditorValidationException("Не задан вариант экспорта");
        }
        try {
            return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new EditorValidationException("Неизвестный вариант экспорта: " + value);
        }
    }
}
