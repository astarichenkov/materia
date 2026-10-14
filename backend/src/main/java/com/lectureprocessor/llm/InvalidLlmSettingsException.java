package com.lectureprocessor.llm;

/** Thrown when an LLM configuration is outside the supported bounds. */
public class InvalidLlmSettingsException extends RuntimeException {

    public InvalidLlmSettingsException(String message) {
        super(message);
    }
}
