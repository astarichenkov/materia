package com.lectureprocessor.multilingual;

import java.time.Instant;

/** One editable terminology mapping used by the translation prompt. */
public record GlossaryEntry(
        long id,
        String term,
        String translation,
        String sourceLanguage,
        String targetLanguage,
        boolean caseSensitive,
        boolean enabled,
        boolean builtin,
        Instant updatedAt) {

    public static GlossaryEntry newEntry(String term, String translation, String source,
            String target, boolean caseSensitive) {
        return new GlossaryEntry(0, term, translation, source, target, caseSensitive, true, false,
                Instant.now());
    }
}
