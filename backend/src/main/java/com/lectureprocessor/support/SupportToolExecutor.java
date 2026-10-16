package com.lectureprocessor.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolRegistry;
import com.lectureprocessor.mcp.McpToolResult;

/**
 * Executes support tools through the existing {@link McpToolRegistry} but only
 * if the tool is on the explicit support allow-list. This is the enforcement
 * point that keeps the support assistant away from the developer assistant's
 * git/filesystem tools and any write or shell capability.
 *
 * <p>Each call runs under an explicit {@link SupportPrincipal} (thread-local) so
 * an in-process invocation cannot bypass the authorization model.</p>
 */
@Service
public class SupportToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(SupportToolExecutor.class);

    private final McpToolRegistry registry;
    private final SupportProperties properties;
    private final SupportScopeGuard scopeGuard;

    public SupportToolExecutor(McpToolRegistry registry, SupportProperties properties,
            SupportScopeGuard scopeGuard) {
        this.registry = registry;
        this.properties = properties;
        this.scopeGuard = scopeGuard;
    }

    /** Allow-listed tool names for the support profile. */
    public List<String> allowedTools() {
        return properties.allowedTools() == null ? List.of() : List.copyOf(properties.allowedTools());
    }

    public boolean isAllowed(String toolName) {
        return toolName != null && allowedTools().contains(toolName);
    }

    public SupportToolCallResult execute(String toolName, Map<String, Object> arguments,
            SupportPrincipal principal) {
        long start = System.currentTimeMillis();
        if (!isAllowed(toolName)) {
            return new SupportToolCallResult(toolName, false,
                    "Инструмент недоступен профилю поддержки: " + toolName, 0);
        }
        McpTool tool = registry.find(toolName).orElse(null);
        if (tool == null) {
            return new SupportToolCallResult(toolName, false, "Инструмент не найден: " + toolName, 0);
        }
        SupportPrincipal effective = principal == null ? scopeGuard.current() : principal;
        try {
            McpToolResult result = SupportAccessContext.callWith(effective,
                    () -> tool.invoke(arguments == null ? Map.of() : arguments));
            long duration = System.currentTimeMillis() - start;
            String text = bound(result.text());
            return new SupportToolCallResult(toolName, !result.isError(), text, duration);
        } catch (SupportAccessDeniedException e) {
            log.debug("Support tool {} denied: {}", toolName, e.getMessage());
            return new SupportToolCallResult(toolName, false, "Доступ запрещён: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            log.debug("Support tool {} failed: {}", toolName, e.getMessage());
            return new SupportToolCallResult(toolName, false,
                    "Ошибка инструмента: " + SecretRedactor.redact(e.getMessage()),
                    System.currentTimeMillis() - start);
        }
    }

    /** Result map helper for tools: redact then truncate to the configured cap. */
    private String bound(String text) {
        String redacted = SecretRedactor.redact(text == null ? "" : text);
        int max = Math.max(200, properties.maxToolResultChars());
        return redacted.length() <= max ? redacted : redacted.substring(0, max) + "\n…[ограничено]";
    }

    /** Convenience builder for JSON-ish tool output maps. */
    public static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }
}
