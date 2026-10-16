package com.lectureprocessor.assistant;

/** Local LLM is unreachable or did not answer within the configured timeout. */
public class AssistantUnavailableException extends RuntimeException {

    public AssistantUnavailableException(String message) {
        super(message);
    }

    public AssistantUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
