package com.lectureprocessor.support.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lectureprocessor.llm.LlmProvider;
import com.lectureprocessor.llm.LlmProviderRegistry;
import com.lectureprocessor.llm.LlmTaskSettingsService;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;
import com.lectureprocessor.support.SecretRedactor;

import tools.jackson.databind.ObjectMapper;

/**
 * MCP tool: model/provider health for the support task.
 *
 * <p>Reports the provider/model selected for {@code SUPPORT_ASSISTANT}, whether
 * the provider is currently reachable and the last known credential status. It
 * performs NO paid cloud request and never returns key material.</p>
 */
@Component
public class GetModelHealthTool implements McpTool {

    private final LlmProviderRegistry registry;
    private final LlmTaskSettingsService taskSettings;
    private final com.lectureprocessor.llm.credential.ProviderCredentialService credentials;
    private final ObjectMapper objectMapper;

    public GetModelHealthTool(LlmProviderRegistry registry, LlmTaskSettingsService taskSettings,
            com.lectureprocessor.llm.credential.ProviderCredentialService credentials,
            ObjectMapper objectMapper) {
        this.registry = registry;
        this.taskSettings = taskSettings;
        this.credentials = credentials;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "get_model_health";
    }

    @Override
    public String description() {
        return "Возвращает провайдера и модель, выбранные для Support Assistant, их доступность и "
                + "последний известный статус подключения. Не выполняет платных запросов. Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.emptyObject();
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        var config = taskSettings.configFor(LlmTaskType.SUPPORT_ASSISTANT);
        String providerId = config.provider();
        LlmProvider provider = registry.byId(providerId).orElse(null);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("task", LlmTaskType.SUPPORT_ASSISTANT.id());
        view.put("provider", providerId);
        view.put("model", config.model());
        view.put("cloud", provider != null && provider.cloud());
        view.put("available", provider != null && provider.isAvailable());
        if (provider == null) {
            view.put("note", "Провайдер не зарегистрирован в этом окружении (нет бина реализации).");
        } else if (!provider.isAvailable()) {
            view.put("note", "Провайдер недоступен: проверьте, запущен ли локальный сервис или настроен ли ключ.");
        }
        if (provider != null && provider.cloud()) {
            var status = credentials.status(providerId);
            Map<String, Object> credential = new LinkedHashMap<>();
            credential.put("configured", status.configured());
            credential.put("lastSuccessAt", status.lastSuccessAt() == null ? null
                    : status.lastSuccessAt().toString());
            credential.put("lastError", SecretRedactor.redact(status.lastError()));
            view.put("credential", credential);
        }
        List<Map<String, Object>> providers = new java.util.ArrayList<>();
        registry.providers().forEach(descriptor -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", descriptor.id());
            entry.put("displayName", descriptor.displayName());
            entry.put("available", descriptor.available());
            entry.put("cloud", descriptor.cloud());
            providers.add(entry);
        });
        view.put("providers", providers);
        return McpToolResult.ok(objectMapper.writeValueAsString(view));
    }
}
