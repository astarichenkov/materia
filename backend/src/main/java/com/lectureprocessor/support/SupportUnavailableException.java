package com.lectureprocessor.support;

/** Raised when the support assistant or its dependencies are unavailable. */
public class SupportUnavailableException extends RuntimeException {

    public SupportUnavailableException(String message) {
        super(message);
    }

    public SupportUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
