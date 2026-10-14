package com.lectureprocessor.llm;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.lectureprocessor.benchmark.BenchmarkRun;
import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.repository.AppSettingRepository;
import com.lectureprocessor.text.PromptTemplates;

import tools.jackson.databind.ObjectMapper;

/**
 * Persists the recommended/default LLM configuration. New lecture-processing
 * jobs read {@link #current()} so the benchmark console can actually influence
 * production behaviour; old jobs keep their own parameters.
 */
@Service
public class LlmSettingsService {

    private final AppSettingRepository settings;
    private final LlmProviderRegistry registry;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public LlmSettingsService(AppSettingRepository settings, LlmProviderRegistry registry,
            AppProperties properties, ObjectMapper objectMapper) {
        this.settings = settings;
        this.registry = registry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** The effective configuration for new jobs: saved default or deployment defaults. */
    public LlmRunConfig current() {
        Optional<LlmRunConfig> saved = settings.get(AppSettingRepository.LLM_DEFAULT_CONFIG)
                .flatMap(this::parse);
        return saved.orElseGet(this::deploymentDefault);
    }

    public void save(LlmRunConfig config) {
        LlmSettingsValidator.validate(config);
        settings.put(AppSettingRepository.LLM_DEFAULT_CONFIG, objectMapper.writeValueAsString(config));
    }

    /** Marks a benchmark run as recommended and stores its configuration as default. */
    public void recommend(BenchmarkRun run) {
        LlmRunConfig config = fromRun(run);
        save(config);
    }

    public LlmRunConfig fromRun(BenchmarkRun run) {
        return new LlmRunConfig(run.provider(), run.model(), run.temperature(), run.maxTokens(),
                run.contextWindow(), run.thinking(), run.promptTemplate());
    }

    private LlmRunConfig deploymentDefault() {
        String model = registry.byId(LlmRunConfig.OLLAMA)
                .map(LlmProvider::defaultChatModel)
                .orElse(properties.ollama().chatModel());
        boolean thinking = !properties.ollama().disableThinking();
        return new LlmRunConfig(LlmRunConfig.OLLAMA, model, 0.1, 1536, 4096, thinking,
                PromptTemplates.STRUCTURED_NOTES);
    }

    private Optional<LlmRunConfig> parse(String json) {
        try {
            return Optional.of(objectMapper.readValue(json, LlmRunConfig.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
