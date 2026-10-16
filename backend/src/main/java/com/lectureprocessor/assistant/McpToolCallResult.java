package com.lectureprocessor.assistant;

/** Result of an MCP tool call made by the assistant. */
public record McpToolCallResult(boolean ok, String text) {
}
