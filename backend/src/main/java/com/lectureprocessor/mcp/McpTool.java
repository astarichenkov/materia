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

    /**
     * MCP profile required to see/call this tool. The default {@code public}
     * profile is visible to every client; specialised profiles (for example
     * {@code file-agent-read}) are only visible when explicitly requested and
     * authorized by the transport.
     */
    default String profile() {
        return "public";
    }

    /** Human/model description of what the tool returns. */
    String description();

    /** JSON Schema (as a plain map) describing accepted arguments. */
    Map<String, Object> inputSchema();

    McpToolResult invoke(Map<String, Object> arguments);
}
