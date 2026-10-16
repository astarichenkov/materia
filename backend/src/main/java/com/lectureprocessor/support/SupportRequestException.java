package com.lectureprocessor.support;

/** Raised when the support surface is disabled or a request is invalid. */
public class SupportRequestException extends RuntimeException {

    public SupportRequestException(String message) {
        super(message);
    }
}
