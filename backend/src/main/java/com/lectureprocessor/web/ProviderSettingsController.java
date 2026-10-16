package com.lectureprocessor.web;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.DeepSeekLlmProvider;
import com.lectureprocessor.llm.LlmProvider;
import com.lectureprocessor.llm.LlmProviderRegistry;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmTaskSettingsService;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ProviderDescriptor;
import com.lectureprocessor.llm.credential.ProviderCredentialService;
import com.lectureprocessor.llm.credential.ProviderCredentialService.CredentialStatus;
import com.lectureprocessor.text.PromptTemplates;
import com.lectureprocessor.web.security.AdminAccessService;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Provider/credential settings UI.
 *
 * <p>The page is the single place to manage AI providers. It replaces the old
 * lecture-only settings page: per-task model assignments, provider status, the
 * DeepSeek API key lifecycle and connection tests live here.</p>
 *
 * <p>Protected by {@code AdminSecurityFilter}; every state-changing form carries
 * a CSRF token.</p>
 */
@Controller
@RequestMapping("/settings")
public class ProviderSettingsController {

    private static final String DEEPSEEK = DeepSeekLlmProvider.PROVIDER_ID;

    private final LlmProviderRegistry registry;
    private final LlmTaskSettingsService taskSettings;
    private final ProviderCredentialService credentials;
    private final PromptTemplates prompts;
    private final AppProperties properties;
    private final AdminAccessService access;
    private final ObjectMapper objectMapper;

    public ProviderSettingsController(LlmProviderRegistry registry, LlmTaskSettingsService taskSettings,
            ProviderCredentialService credentials, PromptTemplates prompts, AppProperties properties,
            AdminAccessService access, ObjectMapper objectMapper) {
        this.registry = registry;
        this.taskSettings = taskSettings;
        this.credentials = credentials;
        this.prompts = prompts;
        this.properties = properties;
        this.access = access;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public String page(Model model, HttpServletRequest request) {
        addModel(model, request);
        return "settings";
    }

    @GetMapping("/login")
    public String loginForm(Model model) {
        model.addAttribute("adminTokenConfigured", access.tokenConfigured());
        return "settings-login";
    }

    @PostMapping("/login")
    public String login(@RequestParam(name = "adminToken", required = false) String adminToken,
            HttpServletRequest request, RedirectAttributes redirect) {
        if (access.login(request, adminToken)) {
            redirect.addFlashAttribute("message", "Вход выполнен");
        } else {
            redirect.addFlashAttribute("error", "Неверный административный токен");
        }
        return "redirect:/settings";
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        access.logout(request.getSession(false));
        return "redirect:/settings";
    }

    @PostMapping("/tasks")
    public String saveTask(@RequestParam("task") String task,
            @RequestParam(name = "provider") String provider,
            @RequestParam("model") String model,
            @RequestParam(name = "temperature", defaultValue = "0.1") double temperature,
            @RequestParam(name = "maxTokens", defaultValue = "1536") int maxTokens,
            @RequestParam(name = "contextWindow", defaultValue = "4096") int contextWindow,
            @RequestParam(name = "thinking", defaultValue = "false") boolean thinking,
            @RequestParam(name = "promptTemplate") String promptTemplate,
            RedirectAttributes redirect) {
        try {
            LlmTaskType type = LlmTaskType.from(task);
            taskSettings.save(type, new LlmRunConfig(provider, model, temperature, maxTokens,
                    contextWindow, thinking, promptTemplate));
            redirect.addFlashAttribute("message", "Настройки задачи «" + type.label() + "» сохранены");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings";
    }

    @PostMapping("/providers/{id}/key")
    public String saveKey(@PathVariable String id, @RequestParam(name = "apiKey", required = false) String apiKey,
            RedirectAttributes redirect) {
        if (apiKey == null || apiKey.isBlank()) {
            redirect.addFlashAttribute("message",
                    "Поле ключа пустое — существующий ключ сохранён без изменений");
            return "redirect:/settings";
        }
        try {
            requireCloudProvider(id);
            credentials.save(id, apiKey);
            redirect.addFlashAttribute("message", "API key для " + id + " сохранён (зашифрован)");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings";
    }

    @PostMapping("/providers/{id}/key/delete")
    public String deleteKey(@PathVariable String id, RedirectAttributes redirect) {
        try {
            requireCloudProvider(id);
            boolean deleted = credentials.delete(id);
            redirect.addFlashAttribute(deleted ? "message" : "error",
                    deleted ? "API key удалён" : "API key не был настроен");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings";
    }

    @PostMapping("/providers/{id}/test")
    public String test(@PathVariable String id, RedirectAttributes redirect) {
        try {
            LlmProvider provider = registry.byId(id).orElseThrow(
                    () -> new IllegalArgumentException("Provider не найден: " + id));
            if (provider instanceof DeepSeekLlmProvider deepSeek) {
                DeepSeekLlmProvider.TestResult result = deepSeek.testConnection();
                if (result.ok()) {
                    credentials.markSuccess(id);
                    redirect.addFlashAttribute("message", "DeepSeek доступен: ответ «"
                            + result.reply() + "», tokens " + result.inputTokens() + "/"
                            + result.outputTokens() + " за " + result.durationMs() + " мс");
                } else {
                    credentials.markError(id, result.error());
                    redirect.addFlashAttribute("error", "DeepSeek недоступен: " + result.error());
                }
            } else if (provider.isAvailable()) {
                redirect.addFlashAttribute("message", provider.displayName() + " доступен, моделей: "
                        + provider.listModels().size());
            } else {
                redirect.addFlashAttribute("error", provider.displayName() + " недоступен");
            }
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings";
    }

    private void addModel(Model model, HttpServletRequest request) {
        List<ProviderDescriptor> providers = registry.providers();
        Map<LlmTaskType, LlmRunConfig> taskConfigs = new EnumMap<>(LlmTaskType.class);
        taskConfigs.putAll(taskSettings.all());

        model.addAttribute("providers", providers);
        model.addAttribute("taskConfigs", taskConfigs);
        model.addAttribute("taskViews", java.util.Arrays.stream(LlmTaskType.values())
                .map(type -> new TaskView(type, taskConfigs.get(type))).toList());
        model.addAttribute("tasks", LlmTaskType.values());
        model.addAttribute("providersJson", providerCatalogJson(providers));
        model.addAttribute("prompts", prompts.list());
        model.addAttribute("deepseekStatus", credentials.status(DEEPSEEK));
        model.addAttribute("deepseekEndpoint", properties.deepseek().baseUrl());
        model.addAttribute("deepseekModel", properties.deepseek().model());
        model.addAttribute("ollamaEndpoint", properties.ollama().baseUrl());
        model.addAttribute("fallbackEnabled", taskSettings.fallbackEnabled());
        model.addAttribute("csrfToken", access.csrfToken(request.getSession(true)));
        model.addAttribute("adminTokenConfigured", access.tokenConfigured());
        model.addAttribute("credentialMasterKeyFallback", credentials.usingFallbackMasterKey());
        model.addAttribute("current", taskConfigs.get(LlmTaskType.LECTURE_PROCESSING));
    }

    private void requireCloudProvider(String id) {
        ProviderDescriptor descriptor = registry.providers().stream()
                .filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Provider не найден: " + id));
        if (!descriptor.cloud() || registry.byId(id).isEmpty()) {
            throw new IllegalArgumentException(
                    "Credentials можно хранить только для реализованных облачных провайдеров");
        }
    }

    /** Exposed for the template: whether a provider has a credential configured. */
    public CredentialStatus status(String providerId) {
        return credentials.status(providerId);
    }

    /**
     * Compact, secret-free provider catalog used by the model-selection script so
     * that switching providers only ever shows that provider's own models.
     */
    private String providerCatalogJson(List<ProviderDescriptor> providers) {
        List<Map<String, Object>> catalog = providers.stream().map(descriptor -> {
            Map<String, Object> entry = new java.util.LinkedHashMap<String, Object>();
            entry.put("id", descriptor.id());
            entry.put("displayName", descriptor.displayName());
            entry.put("available", descriptor.available());
            entry.put("cloud", descriptor.cloud());
            entry.put("models", descriptor.chatModels().stream().map(model -> {
                Map<String, Object> view = new java.util.LinkedHashMap<String, Object>();
                view.put("name", model.name());
                view.put("quantization", model.quantizationOrUnknown());
                return view;
            }).toList());
            return entry;
        }).toList();
        return objectMapper.writeValueAsString(catalog);
    }

    /** One task assignment view; the provider's own models are listed client-side. */
    public record TaskView(LlmTaskType type, LlmRunConfig config) {
    }
}
