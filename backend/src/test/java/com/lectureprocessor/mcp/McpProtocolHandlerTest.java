package com.lectureprocessor.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class McpProtocolHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final McpTool echoTool = new McpTool() {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "Echo tool for tests";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return McpSchemas.object(Map.of("text", McpSchemas.string("Text")), List.of("text"));
        }

        @Override
        public McpToolResult invoke(Map<String, Object> arguments) {
            return McpToolResult.ok("echo:" + arguments.getOrDefault("text", ""));
        }
    };

    private final McpProtocolHandler handler =
            new McpProtocolHandler(new McpToolRegistry(List.of(echoTool)), objectMapper);

    private JsonNode call(String json) {
        return handler.handle(objectMapper.readTree(json)).orElseThrow();
    }

    @Test
    void initializeReturnsProtocolAndCapabilities() {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

        assertThat(response.get("result").get("protocolVersion").asText()).isNotBlank();
        assertThat(response.get("result").get("capabilities").get("tools")).isNotNull();
        assertThat(response.get("result").get("serverInfo").get("name").asText())
                .isEqualTo("lecture-processor");
    }

    @Test
    void listsToolsWithSchema() {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

        JsonNode tools = response.get("result").get("tools");
        assertThat(tools.size()).isEqualTo(1);
        assertThat(tools.get(0).get("name").asText()).isEqualTo("echo");
        assertThat(tools.get(0).get("inputSchema").get("type").asText()).isEqualTo("object");
    }

    @Test
    void callsToolAndReturnsContent() {
        JsonNode response = call("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"echo","arguments":{"text":"hi"}}}
                """);

        JsonNode result = response.get("result");
        assertThat(result.get("isError").asBoolean()).isFalse();
        assertThat(result.get("content").get(0).get("text").asText()).isEqualTo("echo:hi");
    }

    @Test
    void unknownToolReturnsErrorContent() {
        JsonNode response = call("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                 "params":{"name":"missing","arguments":{}}}
                """);

        assertThat(response.get("result").get("isError").asBoolean()).isTrue();
        assertThat(response.get("result").get("content").get(0).get("text").asText())
                .contains("Инструмент не найден");
    }

    @Test
    void unknownMethodReturnsJsonRpcError() {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"bogus\"}");

        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void notificationIsNotAnswered() {
        assertThat(handler.handle(objectMapper.readTree(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))).isEmpty();
    }
}
