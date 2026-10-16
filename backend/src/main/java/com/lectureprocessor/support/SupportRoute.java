package com.lectureprocessor.support;

import java.util.List;

/**
 * Deterministic routing decision: the intent plus the ordered MCP tools that may
 * be called for it. The list is capped by {@code app.support.max-tool-calls}.
 */
public record SupportRoute(SupportIntent intent, List<String> tools) {

    public SupportRoute {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
