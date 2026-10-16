package com.lectureprocessor.codereview;

/** Raised when the model output cannot be parsed as structured review JSON. */
public class ReviewParseException extends RuntimeException {

    public ReviewParseException(String message) {
        super(message);
    }

    public ReviewParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
