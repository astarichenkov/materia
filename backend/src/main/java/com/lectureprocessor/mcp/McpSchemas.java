package com.lectureprocessor.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small helpers for building JSON Schema maps for MCP tools. */
public final class McpSchemas {

    private McpSchemas() {
    }

    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    public static Map<String, Object> emptyObject() {
        return object(Map.of(), List.of());
    }

    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> integer(String description, int min, int max) {
        return Map.of("type", "integer", "description", description,
                "minimum", min, "maximum", max);
    }

    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }
}
