package com.lectureprocessor.multilingual;

/** Failure of an on-demand segment re-translation. */
public class EditorRetranslationException extends RuntimeException {

    private final boolean notFound;

    public EditorRetranslationException(String message, boolean notFound) {
        super(message);
        this.notFound = notFound;
    }

    public static EditorRetranslationException unavailable(String message) {
        return new EditorRetranslationException(message, false);
    }

    public static EditorRetranslationException notFound(String message) {
        return new EditorRetranslationException(message, true);
    }

    public boolean isNotFound() {
        return notFound;
    }
}
