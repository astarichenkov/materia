package com.lectureprocessor.multilingual;

import java.time.Instant;

/**
 * Per-lecture language preference plus the detected language metadata.
 *
 * @param lectureId          lecture id
 * @param languageMode       AUTO / ENGLISH / RUSSIAN (manual override)
 * @param detectedLanguage   language actually detected/used (ISO 639-1)
 * @param languageConfidence confidence reported by the tool; {@code null} when
 *                           whisper.cpp does not provide it (never invented)
 * @param detectionSource    where the language information came from
 * @param translationMode    requested document set
 * @param targetLanguage     translation target language
 * @param autoTranslate      whether an English source is translated automatically
 * @param updatedAt          last update
 */
public record LectureLanguage(
        String lectureId,
        LanguageMode languageMode,
        String detectedLanguage,
        Double languageConfidence,
        LanguageDetectionSource detectionSource,
        TranslationMode translationMode,
        String targetLanguage,
        boolean autoTranslate,
        Instant updatedAt) {

    public static LectureLanguage defaults(String lectureId, String defaultLanguageMode,
            String defaultTranslationMode, String target, boolean autoTranslate) {
        return new LectureLanguage(lectureId, LanguageMode.from(defaultLanguageMode), null, null, null,
                TranslationMode.from(defaultTranslationMode), target, autoTranslate, Instant.now());
    }

    public LectureLanguage withDetection(String language, Double confidence,
            LanguageDetectionSource source) {
        return new LectureLanguage(lectureId, languageMode, language, confidence, source,
                translationMode, targetLanguage, autoTranslate, Instant.now());
    }

    public LectureLanguage withPreferences(LanguageMode mode, TranslationMode translation,
            boolean auto) {
        return new LectureLanguage(lectureId, mode, detectedLanguage, languageConfidence,
                detectionSource, translation, targetLanguage, auto, Instant.now());
    }

    /**
     * Resolves the language the translation stage must use.
     *
     * <p>Russian source is never translated into Russian, so the requested mode
     * collapses to {@link TranslationMode#ORIGINAL_ONLY}. An English source is
     * translated when the user asked for it or when auto-translate is on.</p>
     */
    public TranslationMode resolvedTranslationMode() {
        String source = normalizedSource();
        if ("ru".equals(source)) {
            return TranslationMode.ORIGINAL_ONLY;
        }
        if ("en".equals(source)) {
            if (translationMode.wantsTranslation()) {
                return translationMode;
            }
            return autoTranslate ? TranslationMode.TRANSLATE_TO_RUSSIAN : TranslationMode.ORIGINAL_ONLY;
        }
        // Unknown / other language: never spend cloud tokens guessing. Only a
        // bilingual request is honoured because no EN->RU decision can be made.
        return translationMode == TranslationMode.BILINGUAL
                ? TranslationMode.BILINGUAL : TranslationMode.ORIGINAL_ONLY;
    }

    /** Effective source language used by the pipeline. */
    public String normalizedSource() {
        if (detectedLanguage != null && !detectedLanguage.isBlank()) {
            return detectedLanguage.strip().toLowerCase(java.util.Locale.ROOT);
        }
        if (languageMode.explicit()) {
            return languageMode.whisperCode();
        }
        return "und";
    }

    public boolean translationWanted() {
        return resolvedTranslationMode().wantsTranslation() && !"ru".equals(targetLanguage);
    }
}
