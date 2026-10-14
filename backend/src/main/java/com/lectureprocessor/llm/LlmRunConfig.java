package com.lectureprocessor.llm;

/**
 * Single, provider-independent LLM configuration used by the benchmark console,
 * the settings page and new lecture-processing jobs.
 *
 * <p>Kept deliberately plain (no vendor types) so it can be persisted, rendered
 * in a form and validated in one place.
 */
public record LlmRunConfig(
        String provider,
        String model,
        Double temperature,
        Integer maxTokens,
        Integer contextWindow,
        Boolean thinking,
        String promptTemplate) {

    public static final String OLLAMA = "ollama";
    public static final String CLOUD = "openai";

    public static LlmRunConfig defaults(String model, String promptTemplate) {
        return new LlmRunConfig(OLLAMA, model, 0.1, 1536, 4096, false, promptTemplate);
    }

    public LlmOptions toOptions() {
        return new LlmOptions(model, temperature, maxTokens, contextWindow, 42, thinking);
    }

    public LlmRunConfig withModel(String newModel) {
        return new LlmRunConfig(provider, newModel, temperature, maxTokens, contextWindow, thinking, promptTemplate);
    }
}
