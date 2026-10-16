package com.lectureprocessor.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.mcp.McpProtocolHandler;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolRegistry;

/**
 * MCP server endpoint. Accepts JSON-RPC 2.0 messages over HTTP POST, so external
 * MCP clients (or {@code curl}) can list and call the read-only project tools.
 *
 * <p>When {@code app.mcp.api-key} is set, requests must carry a matching
 * {@code X-MCP-Key} header.</p>
 */
@RestController
public class McpController {

    private final McpProtocolHandler handler;
    private final McpToolRegistry registry;
    private final AppProperties properties;

    public McpController(McpProtocolHandler handler, McpToolRegistry registry,
            AppProperties properties) {
        this.handler = handler;
        this.registry = registry;
        this.properties = properties;
    }

    @PostMapping(path = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(
            @RequestHeader(value = "X-MCP-Key", required = false) String key,
            @RequestBody JsonNode request) {
        if (!authorized(key)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<JsonNode> response = handler.handle(request);
        return response.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.ACCEPTED).build());
    }

    @GetMapping(path = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("server", "lecture-processor");
        info.put("protocol", "mcp-jsonrpc-2.0");
        info.put("protocolVersion", "2025-06-18");
        info.put("description", "Read-only MCP tool surface for the Lecture Processor project");
        info.put("authentication", properties.mcp().apiKey() != null
                && !properties.mcp().apiKey().isBlank() ? "X-MCP-Key header" : "none (local)");
        info.put("tools", registry.tools().stream().map(McpTool::name).toList());
        return info;
    }

    private boolean authorized(String providedKey) {
        String configured = properties.mcp().apiKey();
        if (configured == null || configured.isBlank()) {
            return true;
        }
        return configured.equals(providedKey);
    }
}
