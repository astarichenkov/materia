package com.lectureprocessor.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

/** Discovers all MCP tools registered as Spring beans. */
@Service
public class McpToolRegistry {

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public McpToolRegistry(List<McpTool> discovered) {
        for (McpTool tool : discovered) {
            tools.put(tool.name(), tool);
        }
    }

    public List<McpTool> tools() {
        return List.copyOf(tools.values());
    }

    public Optional<McpTool> find(String name) {
        return Optional.ofNullable(name).map(tools::get);
    }
}
