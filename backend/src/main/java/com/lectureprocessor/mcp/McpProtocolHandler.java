package com.lectureprocessor.mcp;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Minimal Model Context Protocol server implementing JSON-RPC 2.0 methods
 * {@code initialize}, {@code ping}, {@code tools/list} and {@code tools/call}.
 *
 * <p>The same handler serves the HTTP endpoint ({@code POST /mcp}) for external
 * MCP clients and is used in-process by the developer assistant, so both share
 * exactly the same read-only tool surface.</p>
 */
@Service
public class McpProtocolHandler {

    private static final Logger log = LoggerFactory.getLogger(McpProtocolHandler.class);
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SERVER_NAME = "lecture-processor";
    private static final String SERVER_VERSION = "1.0.0";
    private static final TypeReference<Map<String, Object>> ARGUMENTS =
            new TypeReference<>() {
            };

    private final McpToolRegistry registry;
    private final ObjectMapper objectMapper;

    public McpProtocolHandler(McpToolRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * Handles one JSON-RPC message.
     *
     * @return the response, or empty for notifications that must not be answered
     */
    public Optional<JsonNode> handle(JsonNode request) {
        if (request == null || !request.isObject()) {
            return Optional.of(error(objectMapper.nullNode(), -32600, "Invalid Request"));
        }
        JsonNode id = request.get("id");
        String method = text(request, "method");
        if (method == null) {
            return Optional.of(error(id, -32600, "Missing method"));
        }
        if (method.startsWith("notifications/")) {
            return Optional.empty();
        }
        try {
            return switch (method) {
                case "initialize" -> Optional.of(success(id, initialize()));
                case "ping" -> Optional.of(success(id, objectMapper.createObjectNode()));
                case "tools/list" -> Optional.of(success(id, toolsList()));
                case "tools/call" -> Optional.of(success(id, toolsCall(request.get("params"))));
                default -> Optional.of(error(id, -32601, "Method not found: " + method));
            };
        } catch (RuntimeException e) {
            log.warn("MCP request failed: {}", e.getMessage());
            return Optional.of(error(id, -32603, "Internal error: " + e.getMessage()));
        }
    }

    private ObjectNode initialize() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.putObject("capabilities").putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        return result;
    }

    private ObjectNode toolsList() {
        ArrayNode tools = objectMapper.createArrayNode();
        for (McpTool tool : registry.tools()) {
            ObjectNode node = tools.addObject();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.set("inputSchema", objectMapper.valueToTree(tool.inputSchema()));
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private ObjectNode toolsCall(JsonNode params) {
        if (params == null || !params.isObject()) {
            return toolError("tools/call", "Параметры tools/call отсутствуют");
        }
        String name = text(params, "name");
        if (name == null) {
            return toolError("tools/call", "Не задано имя инструмента");
        }
        McpTool tool = registry.find(name).orElse(null);
        if (tool == null) {
            return toolError(name, "Инструмент не найден: " + name);
        }
        Map<String, Object> arguments = Map.of();
        JsonNode argumentsNode = params.get("arguments");
        if (argumentsNode != null && argumentsNode.isObject()) {
            arguments = objectMapper.convertValue(argumentsNode, ARGUMENTS);
        }
        McpToolResult result = tool.invoke(arguments);
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "text");
        content.put("text", result.text());
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("content").add(content);
        response.put("isError", result.isError());
        return response;
    }

    private ObjectNode toolError(String name, String message) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "text");
        content.put("text", message);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("content").add(content);
        response.put("isError", true);
        return response;
    }

    private ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? objectMapper.nullNode() : id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? objectMapper.nullNode() : id);
        response.set("error", error);
        return response;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isString()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }
}
