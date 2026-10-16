package com.lectureprocessor.llm;

/**
 * Classified failures of the DeepSeek (cloud) provider. The message is safe to
 * show to an operator: it never contains the API key, request headers or the
 * raw provider response body.
 */
public class DeepSeekException extends LlmException {

    /** Failure categories the UI/API can react to explicitly. */
    public enum Category {
        MISSING_CREDENTIAL,
        AUTHENTICATION,
        INSUFFICIENT_BALANCE,
        RATE_LIMIT,
        TIMEOUT,
        UNAVAILABLE,
        UNSUPPORTED_MODEL,
        INVALID_REQUEST,
        EMPTY_COMPLETION,
        TRUNCATED,
        MALFORMED_RESPONSE,
        CONFIGURATION
    }

    private final Category category;
    private final int statusCode;

    public DeepSeekException(Category category, String message, int statusCode) {
        super(message);
        this.category = category;
        this.statusCode = statusCode;
    }

    public DeepSeekException(Category category, String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.statusCode = statusCode;
    }

    public Category category() {
        return category;
    }

    public int statusCode() {
        return statusCode;
    }
}
