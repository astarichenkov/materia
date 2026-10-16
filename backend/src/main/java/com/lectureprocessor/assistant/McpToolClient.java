package com.lectureprocessor.assistant;

import java.util.Map;

/**
 * Client used by the developer assistant to invoke MCP tools. The default
 * implementation speaks the MCP JSON-RPC protocol through the same handler that
 * serves the HTTP {@code /mcp} endpoint.
 */
public interface McpToolClient {

    McpToolCallResult call(String toolName, Map<String, Object> arguments);
}
