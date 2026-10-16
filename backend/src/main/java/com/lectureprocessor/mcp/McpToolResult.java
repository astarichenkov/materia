package com.lectureprocessor.mcp;

/** Result of an MCP tool invocation. {@code isError} maps to the MCP wire flag. */
public record McpToolResult(boolean isError, String text) {

    public static McpToolResult ok(String text) {
        return new McpToolResult(false, text == null ? "" : text);
    }

    public static McpToolResult error(String text) {
        return new McpToolResult(true, text == null ? "" : text);
    }
}
