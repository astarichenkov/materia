package com.lectureprocessor.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.CloudCostService;
import com.lectureprocessor.llm.DeepSeekLlmProvider;
import com.lectureprocessor.llm.LlmProvider;
import com.lectureprocessor.llm.LlmProviderRegistry;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmTaskSettingsService;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ProviderDescriptor;
import com.lectureprocessor.llm.credential.ProviderCredentialService;
import com.lectureprocessor.llm.credential.ProviderCredentialService.CredentialStatus;

/**
 * Administrative REST API for provider selection and credential lifecycle.
 *
 * <p>Guarded by {@code AdminSecurityFilter}. The plaintext API key is accepted
 * on write but NEVER returned: responses only expose a masked
 * {@link CredentialStatus}. The key is never logged.</p>
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderApiController {

    private final LlmProviderRegistry registry;
    private final LlmTaskSettingsService taskSettings;
    private final ProviderCredentialService credentials;
    private final AppProperties properties;
    private final CloudCostService costs;

    public ProviderApiController(LlmProviderRegistry registry, LlmTaskSettingsService taskSettings,
            ProviderCredentialService credentials, AppProperties properties, CloudCostService costs) {
        this.registry = registry;
        this.taskSettings = taskSettings;
        this.credentials = credentials;
        this.properties = properties;
        this.costs = costs;
    }

    @GetMapping
    public List<Map<String, Object>> providers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProviderDescriptor descriptor : registry.providers()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", descriptor.id());
            view.put("displayName", descriptor.displayName());
            view.put("available", descriptor.available());
            view.put("cloud", descriptor.cloud());
            view.put("models", descriptor.chatModels());
            if (descriptor.cloud()) {
                view.put("credential", credentials.status(descriptor.id()));
            }
            result.add(view);
        }
        return result;
    }

    @GetMapping("/{id}/models")
    public List<?> models(@PathVariable String id) {
        return registry.chatModels(id);
    }

    @PostMapping("/{id}/credential")
    public CredentialStatus saveCredential(@PathVariable String id, @RequestBody CredentialRequest request) {
        requireCloud(id);
        return credentials.save(id, request == null ? null : request.apiKey());
    }

    @DeleteMapping("/{id}/credential")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCredential(@PathVariable String id) {
        requireCloud(id);
        credentials.delete(id);
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String id) {
        LlmProvider provider = registry.byId(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "provider not found: " + id));
        if (provider instanceof DeepSeekLlmProvider deepSeek) {
            DeepSeekLlmProvider.TestResult result = deepSeek.testConnection();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", result.ok());
            response.put("provider", id);
            response.put("model", deepSeek.defaultChatModel());
            response.put("inputTokens", result.inputTokens());
            response.put("outputTokens", result.outputTokens());
            response.put("durationMs", result.durationMs());
            if (result.ok()) {
                response.put("reply", result.reply());
                credentials.markSuccess(id);
            } else {
                response.put("error", result.error());
                credentials.markError(id, result.error());
            }
            return response;
        }
        boolean available = provider.isAvailable();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", available);
        response.put("provider", id);
        response.put("model", provider.defaultChatModel());
        response.put("models", registry.chatModels(id));
        if (!available) {
            response.put("error", "provider недоступен: " + id);
        }
        return response;
    }

    @GetMapping("/pricing")
    public Map<String, Object> pricing() {
        AppProperties.DeepSeek.Pricing pricing = properties.deepseek().pricing();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("currency", costs.currency());
        response.put("inputPerMillionTokens", pricing.inputPerMillionTokens());
        response.put("outputPerMillionTokens", pricing.outputPerMillionTokens());
        response.put("cacheHitPerMillionTokens", pricing.cacheHitPerMillionTokens());
        response.put("note", "Все стоимости рассчитываются как estimate по настраиваемому тарифу.");
        return response;
    }

    // --- per-task model assignments ---

    @GetMapping("/tasks")
    public Map<String, LlmRunConfig> tasks() {
        Map<String, LlmRunConfig> result = new LinkedHashMap<>();
        taskSettings.all().forEach((task, config) -> result.put(task.id(), config));
        return result;
    }

    @PutMapping("/tasks/{task}")
    public LlmRunConfig saveTask(@PathVariable String task, @RequestBody LlmRunConfig config) {
        LlmTaskType type = LlmTaskType.from(task);
        // Never accept a cloud assignment that cannot actually run.
        if (config != null && config.provider() != null && !config.provider().isBlank()) {
            Optional<LlmProvider> provider = registry.byId(config.provider());
            if (provider.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "provider не зарегистрирован: " + config.provider());
            }
        }
        taskSettings.save(type, config);
        return taskSettings.configFor(type);
    }

    private void requireCloud(String id) {
        ProviderDescriptor descriptor = registry.providers().stream()
                .filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found: " + id));
        if (!descriptor.cloud()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Credentials можно хранить только для облачных провайдеров");
        }
        if (registry.byId(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Облачный провайдер не реализован: " + id);
        }
    }

    /** Write-only DTO; the key is never serialized back. */
    public record CredentialRequest(String apiKey) {
    }
}
