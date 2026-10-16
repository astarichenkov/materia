package com.lectureprocessor.llm;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.credential.ProviderCredentialService;
import com.lectureprocessor.repository.AppSettingRepository;
import com.lectureprocessor.text.PromptTemplates;

import tools.jackson.databind.ObjectMapper;

/**
 * Per-task model routing and persistence.
 *
 * <p>Each logical task ({@link LlmTaskType}) has its own configuration stored
 * under {@code llm.task-config.<task>}. Lecture processing keeps using the
 * existing {@code llm.default-config} key through {@link LlmSettingsService} so
 * already-running installations and their checkpoints stay compatible.
 * Changing one task never changes another.</p>
 *
 * <p>Fallback to Ollama is off by default and, when enabled, is always reported
 * explicitly in {@link ResolvedLlmTask#fallback()} — never silent.</p>
 */
@Service
public class LlmTaskSettingsService implements LlmTaskResolver {

    private static final Logger log = LoggerFactory.getLogger(LlmTaskSettingsService.class);
    private static final String KEY_PREFIX = "llm.task-config.";

    private final AppSettingRepository settings;
    private final LlmSettingsService lectureSettings;
    private final LlmProviderRegistry registry;
    private final AppProperties properties;
    private final ProviderCredentialService credentials;
    private final ObjectMapper objectMapper;

    public LlmTaskSettingsService(AppSettingRepository settings, LlmSettingsService lectureSettings,
            LlmProviderRegistry registry, AppProperties properties, ProviderCredentialService credentials,
            ObjectMapper objectMapper) {
        this.settings = settings;
        this.lectureSettings = lectureSettings;
        this.registry = registry;
        this.properties = properties;
        this.credentials = credentials;
        this.objectMapper = objectMapper;
    }

    /** Effective configuration for a task: saved value or a safe default. */
    public LlmRunConfig configFor(LlmTaskType task) {
        if (task == LlmTaskType.LECTURE_PROCESSING) {
            return lectureSettings.current();
        }
        return settings.get(key(task)).flatMap(this::parse).orElseGet(() -> defaultFor(task));
    }

    /** Persists and validates the configuration for a task. */
    public void save(LlmTaskType task, LlmRunConfig config) {
        LlmSettingsValidator.validate(config);
        if (task == LlmTaskType.LECTURE_PROCESSING) {
            lectureSettings.save(config);
            return;
        }
        settings.put(key(task), objectMapper.writeValueAsString(config));
    }

    /** All effective task configurations, keyed by task, for the settings UI. */
    public Map<LlmTaskType, LlmRunConfig> all() {
        Map<LlmTaskType, LlmRunConfig> result = new EnumMap<>(LlmTaskType.class);
        for (LlmTaskType task : LlmTaskType.values()) {
            result.put(task, configFor(task));
        }
        return result;
    }

    public boolean fallbackEnabled() {
        return properties.llm() != null && properties.llm().fallbackEnabled();
    }

    @Override
    public ResolvedLlmTask resolve(LlmTaskType task) {
        LlmRunConfig config = configFor(task);
        LlmProvider primary = registry.byId(config.provider()).orElse(null);
        if (primary != null && (!fallbackEnabled() || primary.isAvailable())) {
            return new ResolvedLlmTask(task, config, primary, config.toOptions(), false, null, primary.cloud());
        }
        if (primary != null) {
            // Fallback explicitly enabled and the primary is unreachable.
            LlmProvider ollama = registry.byId(LlmRunConfig.OLLAMA).orElse(primary);
            log.warn("Task {} falling back from {} to {} (fallback enabled)", task, primary.id(), ollama.id());
            LlmRunConfig fallbackConfig = config.withModel(ollama.defaultChatModel());
            return new ResolvedLlmTask(task, fallbackConfig, ollama, fallbackConfig.toOptions(), true,
                    "Провайдер " + primary.id() + " недоступен", ollama.cloud());
        }
        // Unknown/unconfigured provider id: only Ollama can serve as a fallback.
        LlmProvider ollama = registry.byId(LlmRunConfig.OLLAMA).orElseThrow(() -> new LlmException(
                "LLM provider недоступен: " + config.provider()));
        LlmRunConfig fallbackConfig = config.withModel(ollama.defaultChatModel());
        return new ResolvedLlmTask(task, fallbackConfig, ollama, fallbackConfig.toOptions(), true,
                "Провайдер " + config.provider() + " не зарегистрирован", ollama.cloud());
    }

    /**
     * Resolved provider id actually used for a task. Used by the UI to show the
     * effective provider/model, including any explicit fallback.
     */
    public String effectiveProviderId(LlmTaskType task) {
        return configFor(task).provider();
    }

    /** Default configuration for a task, honouring available providers. */
    public LlmRunConfig defaultFor(LlmTaskType task) {
        return switch (task) {
            case LECTURE_PROCESSING -> lectureSettings.current();
            case DEVELOPER_ASSISTANT -> ollamaDefault(0.1, 2048, 8192, false);
            case CODE_REVIEW -> codeReviewDefault();
            case SUPPORT_ASSISTANT -> ollamaDefault(0.1, 1536, 8192, false);
        };
    }

    private LlmRunConfig codeReviewDefault() {
        boolean deepSeekReady = properties.deepseek().enabled() && credentials.isConfigured(DeepSeekLlmProvider.PROVIDER_ID);
        if (deepSeekReady) {
            return new LlmRunConfig(DeepSeekLlmProvider.PROVIDER_ID, properties.deepseek().model(), 0.1, 2048,
                    8192, false, PromptTemplates.STRUCTURED_NOTES);
        }
        return ollamaDefault(0.1, 2048, 8192, false);
    }

    private LlmRunConfig ollamaDefault(double temperature, int maxTokens, int contextWindow, boolean thinking) {
        String model = registry.byId(LlmRunConfig.OLLAMA).map(LlmProvider::defaultChatModel)
                .orElse(properties.ollama().chatModel());
        return new LlmRunConfig(LlmRunConfig.OLLAMA, model, temperature, maxTokens, contextWindow, thinking,
                PromptTemplates.STRUCTURED_NOTES);
    }

    private Optional<LlmRunConfig> parse(String json) {
        try {
            return Optional.of(objectMapper.readValue(json, LlmRunConfig.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String key(LlmTaskType task) {
        return KEY_PREFIX + task.id();
    }
}
