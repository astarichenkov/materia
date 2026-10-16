package com.lectureprocessor.assistant;

/** Records which MCP tool was used to answer a question and whether it succeeded. */
public record AssistantToolCall(String name, boolean ok, String summary) {
}
