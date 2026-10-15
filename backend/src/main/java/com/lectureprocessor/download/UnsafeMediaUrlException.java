package com.lectureprocessor.download;

/**
 * Raised when a media source URL is rejected (unsupported platform, unsafe
 * scheme/host, SSRF attempt, private address).
 */
public class UnsafeMediaUrlException extends RuntimeException {

    public UnsafeMediaUrlException(String message) {
        super(message);
    }
}
