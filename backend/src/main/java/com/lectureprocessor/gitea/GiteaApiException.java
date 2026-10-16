package com.lectureprocessor.gitea;

/**
 * Failure talking to Gitea. Classified so the review pipeline and UI can react
 * (auth, not found, forbidden, rate limit, unavailable, invalid response). The
 * message never contains the API token.
 */
public class GiteaApiException extends RuntimeException {

    public enum Category {
        AUTHENTICATION,
        FORBIDDEN,
        NOT_FOUND,
        RATE_LIMIT,
        UNAVAILABLE,
        INVALID_RESPONSE,
        CONFIGURATION
    }

    private final Category category;
    private final int statusCode;

    public GiteaApiException(Category category, String message, int statusCode) {
        super(message);
        this.category = category;
        this.statusCode = statusCode;
    }

    public GiteaApiException(Category category, String message, int statusCode, Throwable cause) {
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
