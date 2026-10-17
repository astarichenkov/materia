package com.lectureprocessor.web;

/**
 * JSON body for {@code POST /api/lectures/from-url}.
 */
public record FromUrlRequest(
        String url,
        String quality,
        Boolean audioOnly,
        Boolean clean,
        Boolean slides,
        String languageMode,
        String translationMode,
        Boolean autoTranslate) {

    public String languageModeOrDefault() {
        return languageMode == null || languageMode.isBlank() ? "AUTO" : languageMode;
    }

    public String translationModeOrDefault() {
        return translationMode == null || translationMode.isBlank()
                ? "TRANSLATE_TO_RUSSIAN" : translationMode;
    }

    public boolean autoTranslateOrDefault() {
        return autoTranslate == null || autoTranslate;
    }

    public boolean audioOnlyOrDefault() {
        return audioOnly != null && audioOnly;
    }

    public boolean cleanOrDefault() {
        return clean != null && clean;
    }

    public boolean slidesOrDefault() {
        return slides != null && slides;
    }
}
