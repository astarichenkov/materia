package com.lectureprocessor.mcp;

import java.util.Map;

/**
 * Defensive argument parsing for MCP tools. Tool inputs are model-controlled, so
 * every value is validated and clamped instead of trusted.
 */
public final class McpArguments {

    private McpArguments() {
    }

    public static String string(Map<String, Object> arguments, String key, String fallback) {
        if (arguments == null) {
            return fallback;
        }
        Object value = arguments.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? fallback : text;
    }

    public static int integer(Map<String, Object> arguments, String key, int fallback, int min, int max) {
        if (arguments == null) {
            return fallback;
        }
        Object value = arguments.get(key);
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value instanceof String text) {
            try {
                parsed = Integer.parseInt(text.strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        } else {
            return fallback;
        }
        return Math.max(min, Math.min(max, parsed));
    }

    public static boolean flag(Map<String, Object> arguments, String key, boolean fallback) {
        if (arguments == null) {
            return fallback;
        }
        Object value = arguments.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
