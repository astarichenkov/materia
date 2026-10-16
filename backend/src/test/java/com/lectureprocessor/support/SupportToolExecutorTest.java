package com.lectureprocessor.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolRegistry;
import com.lectureprocessor.mcp.McpToolResult;

class SupportToolExecutorTest {

    private static SupportProperties props() {
        return new SupportProperties(true, "test", "install", "support-assistant-v1",
                List.of("allowed_tool"), 8, 100, 4000, 10, 16000, 2, 6, 0.15, Duration.ofMinutes(1));
    }

    private static McpTool tool(String name, java.util.function.Supplier<McpToolResult> body) {
        return new McpTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public McpToolResult invoke(Map<String, Object> arguments) {
                return body.get();
            }
        };
    }

    private SupportToolExecutor executor(List<McpTool> tools) {
        SupportProperties properties = props();
        SupportAccessService access = new SupportAccessService(properties);
        return new SupportToolExecutor(new McpToolRegistry(tools), properties,
                new SupportScopeGuard(access));
    }

    @Test
    void disallowsToolOutsideSupportProfile() {
        SupportToolExecutor executor = executor(List.of(
                tool("git_status", () -> McpToolResult.ok("branch main"))));
        SupportToolCallResult result = executor.execute("git_status", Map.of(), null);
        assertThat(result.ok()).isFalse();
        assertThat(result.text()).contains("недоступен профилю");
    }

    @Test
    void missingAllowedToolIsReported() {
        SupportToolExecutor executor = executor(List.of());
        SupportToolCallResult result = executor.execute("allowed_tool", Map.of(), null);
        assertThat(result.ok()).isFalse();
        assertThat(result.text()).contains("не найден");
    }

    @Test
    void successfulToolIsBoundedAndRedacted() {
        SupportToolExecutor executor = executor(List.of(tool("allowed_tool",
                () -> McpToolResult.ok("api_key=supersecretvalue " + "x".repeat(500)))));
        SupportToolCallResult result = executor.execute("allowed_tool", Map.of(), null);
        assertThat(result.ok()).isTrue();
        assertThat(result.text()).doesNotContain("supersecretvalue").contains("[REDACTED]");
        assertThat(result.text()).contains("ограничено");
        assertThat(result.text().length()).isLessThanOrEqualTo(220);
    }

    @Test
    void throwingToolBecomesErrorResultNotException() {
        SupportToolExecutor executor = executor(List.of(tool("allowed_tool", () -> {
            throw new IllegalStateException("boom");
        })));
        SupportToolCallResult result = executor.execute("allowed_tool", Map.of(), null);
        assertThat(result.ok()).isFalse();
        assertThat(result.text()).contains("Ошибка инструмента");
    }
}
