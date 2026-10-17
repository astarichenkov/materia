package com.lectureprocessor.fileagent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.fileagent.model.FileAgentToolCall;
import com.lectureprocessor.fileagent.repository.FileAgentToolCallRepository;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolRegistry;
import com.lectureprocessor.mcp.McpToolResult;
import com.lectureprocessor.support.SecretRedactor;

/**
 * Executes only the read-only tools declared for the file-agent MCP profile.
 * The allow-list is derived from the tool profile, so public git/filesystem
 * developer tools can never be reached from a file-agent task.
 */
@Service
public class FileAgentToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileAgentToolExecutor.class);
    private static final int MAX_RESULT_CHARS = 12_000;

    private final McpToolRegistry registry;
    private final FileAgentProperties properties;
    private final FileAgentToolCallRepository traces;

    public FileAgentToolExecutor(McpToolRegistry registry, FileAgentProperties properties,
            FileAgentToolCallRepository traces) {
        this.registry = registry;
        this.properties = properties;
        this.traces = traces;
    }

    public List<String> allowedTools() {
        String profile = properties.profile();
        return registry.tools().stream()
                .filter(tool -> profile.equals(tool.profile()))
                .map(McpTool::name)
                .toList();
    }

    public boolean isAllowed(String toolName) {
        if (toolName == null) {
            return false;
        }
        return registry.find(toolName)
                .map(tool -> properties.profile().equals(tool.profile()))
                .orElse(false);
    }

    public ToolExecution execute(String taskId, String toolName, Map<String, Object> arguments,
            AtomicInteger budget) {
        long start = System.currentTimeMillis();
        String args = summarizeArguments(arguments);
        if (budget.getAndIncrement() >= Math.max(1, properties.maxToolCalls())) {
            return record(taskId, toolName, args, false,
                    "Превышен лимит вызовов инструментов (" + properties.maxToolCalls() + ")",
                    start);
        }
        if (!isAllowed(toolName)) {
            return record(taskId, toolName, args, false,
                    "Инструмент не разрешён профилю " + properties.profile(), start);
        }
        McpTool tool = registry.find(toolName).orElse(null);
        if (tool == null) {
            return record(taskId, toolName, args, false, "Инструмент не найден", start);
        }
        try {
            McpToolResult result = tool.invoke(arguments == null ? Map.of() : arguments);
            String text = bound(SecretRedactor.redact(result.text()));
            return record(taskId, toolName, args, !result.isError(), text, start);
        } catch (RuntimeException e) {
            log.debug("File agent tool {} failed: {}", toolName, e.getMessage());
            return record(taskId, toolName, args, false,
                    "Ошибка инструмента: " + SecretRedactor.redact(e.getMessage()), start);
        }
    }

    private ToolExecution record(String taskId, String toolName, String args, boolean ok,
            String text, long start) {
        long duration = System.currentTimeMillis() - start;
        traces.insert(new FileAgentToolCall(null, taskId, toolName, args, ok, duration,
                truncate(text, 600), Instant.now()));
        return new ToolExecution(toolName, ok, text, duration);
    }

    private static String summarizeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        StringBuilder text = new StringBuilder();
        arguments.forEach((key, value) -> {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(key).append('=').append(truncate(String.valueOf(value), 120));
        });
        return truncate(SecretRedactor.redact(text.toString()), 1000);
    }

    private static String bound(String text) {
        return truncate(text, MAX_RESULT_CHARS);
    }

    private static String truncate(String text, int max) {
        String value = text == null ? "" : text;
        return value.length() <= max ? value : value.substring(0, max) + "\n…[ограничено]";
    }

    /** One tool invocation result, also persisted in the tool trace. */
    public record ToolExecution(String toolName, boolean ok, String text, long durationMs) {
    }
}
