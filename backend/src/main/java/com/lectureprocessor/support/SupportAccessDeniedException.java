package com.lectureprocessor.support;

/** Raised when a support operation targets a ticket outside the caller's scope. */
public class SupportAccessDeniedException extends RuntimeException {

    public SupportAccessDeniedException(String message) {
        super(message);
    }
}
