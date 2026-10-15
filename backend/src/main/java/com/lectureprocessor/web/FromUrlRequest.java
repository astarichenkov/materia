package com.lectureprocessor.web;

/**
 * JSON body for {@code POST /api/lectures/from-url}.
 */
public record FromUrlRequest(
        String url,
        String quality,
        Boolean audioOnly,
        Boolean clean,
        Boolean slides) {

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
