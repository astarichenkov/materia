package com.lectureprocessor.llm;

/**
 * Per-request LLM generation options. Kept independent from any vendor SDK so
 * callers and benchmarks can vary model/context/thinking without touching
 * provider code.
 *
 * @param thinking {@code true} to enable reasoning, {@code false} to disable,
 *                 {@code null} to fall back to the provider default
 */
public record LlmOptions(
        String model,
        Double temperature,
        Integer maxTokens,
        Integer numCtx,
        Integer seed,
        Boolean thinking) {

    public LlmOptions(String model, Double temperature, Integer maxTokens, Integer numCtx, Integer seed) {
        this(model, temperature, maxTokens, numCtx, seed, null);
    }

    /**
     * Default generation options. {@code maxTokens} must be large enough that
     * qwen3's reasoning tokens do not starve the final answer, and
     * {@code numCtx} must hold the prompt plus that budget.
     */
    public static LlmOptions of(String model) {
        return new LlmOptions(model, 0.2, 6144, 8192, 42, null);
    }

    public LlmOptions withModel(String newModel) {
        return new LlmOptions(newModel, temperature, maxTokens, numCtx, seed, thinking);
    }

    public LlmOptions withNumCtx(int newNumCtx) {
        return new LlmOptions(model, temperature, maxTokens, newNumCtx, seed, thinking);
    }

    public LlmOptions withTemperature(double newTemperature) {
        return new LlmOptions(model, newTemperature, maxTokens, numCtx, seed, thinking);
    }

    public LlmOptions withMaxTokens(int newMaxTokens) {
        return new LlmOptions(model, temperature, newMaxTokens, numCtx, seed, thinking);
    }

    public LlmOptions withThinking(Boolean newThinking) {
        return new LlmOptions(model, temperature, maxTokens, numCtx, seed, newThinking);
    }
}
