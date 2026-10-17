package com.lectureprocessor.discovery;

/** Safe, user-facing provider HTTP failure (never contains credentials). */
public class DiscoveryHttpException extends RuntimeException {

    public DiscoveryHttpException(String message) {
        super(message);
    }

    public DiscoveryHttpException(String message, Throwable cause) {
        super(message, cause);
    }
}
