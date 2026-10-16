package com.lectureprocessor.mcp;

import java.util.Map;

/**
 * A read-only tool exposed through the MCP endpoint. Implementations must never
 * mutate the repository, run destructive git commands or read files outside the
 * project root.
 */
public interface McpTool {

    /** Machine name used in {@code tools/call}. */
    String name();

    /** Human/model description of what the tool returns. */
    String description();

    /** JSON Schema (as a plain map) describing accepted arguments. */
    Map<String, Object> inputSchema();

    McpToolResult invoke(Map<String, Object> arguments);
}
