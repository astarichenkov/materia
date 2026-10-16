package com.lectureprocessor.web;

/** Request body for {@code POST /api/assistant/help}. */
public record AssistantHelpRequest(String question, String sessionId) {
}
