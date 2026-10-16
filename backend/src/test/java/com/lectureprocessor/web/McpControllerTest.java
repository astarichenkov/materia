package com.lectureprocessor.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.mcp.McpProtocolHandler;
import com.lectureprocessor.mcp.McpToolRegistry;

@WebMvcTest(controllers = McpController.class)
class McpControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private McpProtocolHandler handler;
    @MockitoBean
    private McpToolRegistry registry;
    @MockitoBean
    private AppProperties properties;

    private void allowAnonymous() {
        when(properties.mcp()).thenReturn(new AppProperties.Mcp("", 20000));
    }

    @Test
    void toolsListIsReturnedAsJsonRpc() throws Exception {
        allowAnonymous();
        when(handler.handle(any())).thenReturn(Optional.of(objectMapper.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}")));

        mockMvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.result.tools").isArray());
    }

    @Test
    void notificationReturns202() throws Exception {
        allowAnonymous();
        when(handler.handle(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void missingApiKeyIsUnauthorizedWhenConfigured() throws Exception {
        when(properties.mcp()).thenReturn(new AppProperties.Mcp("secret", 20000));

        mockMvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void matchingApiKeyIsAccepted() throws Exception {
        when(properties.mcp()).thenReturn(new AppProperties.Mcp("secret", 20000));
        when(handler.handle(any())).thenReturn(Optional.of(objectMapper.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")));

        mockMvc.perform(post("/mcp")
                        .header("X-MCP-Key", "secret")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void infoEndpointDescribesServer() throws Exception {
        allowAnonymous();
        when(registry.tools()).thenReturn(List.of());

        mockMvc.perform(get("/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.server").value("lecture-processor"))
                .andExpect(jsonPath("$.tools").isArray());
    }
}
