package com.lectureprocessor.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** File-agent tools are invisible and uncallable from the public MCP profile. */
class McpProfileIsolationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publicProfileCannotListOrCallFileTools() {
        McpTool publicTool = tool("git_status", "public");
        McpTool fileTool = tool("read_project_file", "file-agent-read");
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpToolRegistry(List.of(publicTool, fileTool)), objectMapper);

        JsonNode publicList = handler.handle(request("tools/list", null), "public").orElseThrow();
        assertThat(publicList.toString()).contains("git_status").doesNotContain("read_project_file");

        JsonNode call = handler.handle(request("tools/call", "read_project_file"), "public").orElseThrow();
        assertThat(call.toString()).contains("\"isError\":true").doesNotContain("file content");
    }

    @Test
    void fileAgentProfileSeesPublicAndFileTools() {
        McpTool publicTool = tool("git_status", "public");
        McpTool fileTool = tool("read_project_file", "file-agent-read");
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpToolRegistry(List.of(publicTool, fileTool)), objectMapper);

        JsonNode list = handler.handle(request("tools/list", null), "file-agent-read").orElseThrow();
        assertThat(list.toString()).contains("git_status", "read_project_file");

        JsonNode call = handler.handle(request("tools/call", "read_project_file"),
                "file-agent-read").orElseThrow();
        assertThat(call.toString()).contains("file content");
    }

    private JsonNode request(String method, String toolName) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        if (toolName != null) {
            body.put("params", Map.of("name", toolName, "arguments", Map.of("path", "README.md")));
        }
        return objectMapper.valueToTree(body);
    }

    private static McpTool tool(String name, String profile) {
        return new McpTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String profile() {
                return profile;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return McpSchemas.emptyObject();
            }

            @Override
            public McpToolResult invoke(Map<String, Object> arguments) {
                return McpToolResult.ok("file content");
            }
        };
    }
}
