package com.lectureprocessor.llm;

/**
 * Validates {@link LlmRunConfig} bounds so the benchmark never sends an
 * obviously unsafe request (e.g. a context window smaller than the generation
 * budget, or a temperature outside Ollama's supported range).
 */
public final class LlmSettingsValidator {

    public static final double MIN_TEMPERATURE = 0.0;
    public static final double MAX_TEMPERATURE = 2.0;
    public static final int MIN_MAX_TOKENS = 64;
    public static final int MAX_MAX_TOKENS = 8192;
    public static final int MIN_CONTEXT = 1024;
    public static final int MAX_CONTEXT = 32768;

    private LlmSettingsValidator() {
    }

    public static void validate(LlmRunConfig config) {
        if (config == null) {
            throw new InvalidLlmSettingsException("Конфигурация LLM не задана");
        }
        if (isBlank(config.provider())) {
            throw new InvalidLlmSettingsException("Не выбран LLM provider");
        }
        if (isBlank(config.model())) {
            throw new InvalidLlmSettingsException("Не выбрана модель");
        }
        Double temperature = config.temperature();
        if (temperature == null || temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE) {
            throw new InvalidLlmSettingsException(
                    "temperature должна быть в диапазоне " + MIN_TEMPERATURE + "–" + MAX_TEMPERATURE);
        }
        Integer maxTokens = config.maxTokens();
        if (maxTokens == null || maxTokens < MIN_MAX_TOKENS || maxTokens > MAX_MAX_TOKENS) {
            throw new InvalidLlmSettingsException(
                    "max tokens должен быть в диапазоне " + MIN_MAX_TOKENS + "–" + MAX_MAX_TOKENS);
        }
        Integer context = config.contextWindow();
        if (context == null || context < MIN_CONTEXT || context > MAX_CONTEXT) {
            throw new InvalidLlmSettingsException(
                    "context window должен быть в диапазоне " + MIN_CONTEXT + "–" + MAX_CONTEXT);
        }
        if (context <= maxTokens) {
            throw new InvalidLlmSettingsException(
                    "context window (" + context + ") должен быть больше max tokens (" + maxTokens + ")");
        }
        if (isBlank(config.promptTemplate())) {
            throw new InvalidLlmSettingsException("Не выбран prompt template");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
