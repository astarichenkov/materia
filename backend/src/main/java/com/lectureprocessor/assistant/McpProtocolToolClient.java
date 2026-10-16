package com.lectureprocessor.assistant;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.lectureprocessor.mcp.McpProtocolHandler;

/**
 * MCP client that routes {@code tools/call} requests through the MCP protocol
 * handler. This keeps the assistant on the same tool surface and wire contract
 * that external MCP clients use over HTTP.
 */
@Service
public class McpProtocolToolClient implements McpToolClient {

    private final McpProtocolHandler handler;
    private final ObjectMapper objectMapper;
    private final AtomicLong ids = new AtomicLong(1);

    public McpProtocolToolClient(McpProtocolHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpToolCallResult call(String toolName, Map<String, Object> arguments) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", ids.getAndIncrement());
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", toolName);
        params.set("arguments",
                objectMapper.valueToTree(arguments == null ? Map.of() : arguments));

        JsonNode response = handler.handle(request).orElse(null);
        if (response == null) {
            return new McpToolCallResult(false, "MCP: пустой ответ");
        }
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            return new McpToolCallResult(false, "MCP error: " + error.path("message").asText(""));
        }
        JsonNode result = response.get("result");
        if (result == null || !result.isObject()) {
            return new McpToolCallResult(false, "MCP: отсутствует result");
        }
        boolean isError = result.path("isError").asBoolean(false);
        JsonNode content = result.get("content");
        String text = "";
        if (content != null && content.isArray() && !content.isEmpty()) {
            text = content.get(0).path("text").asText("");
        }
        return new McpToolCallResult(!isError, text);
    }
}
