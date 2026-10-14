package com.lectureprocessor.llm;

import java.util.List;

/**
 * Provider abstraction for local LLM chat and embeddings. Business logic only
 * depends on this interface, so the concrete backend (Spring AI/Ollama now,
 * something else later) can be replaced without rewrites.
 */
public interface LlmProvider {

    /** Stable provider id used in configuration and the API, e.g. {@code ollama}. */
    String id();

    /** Human-readable provider name shown in the UI. */
    String displayName();

    /** True for a remote/cloud provider (used to show it as unavailable). */
    default boolean cloud() {
        return false;
    }

    /** Single-turn generation. */
    String chat(String systemPrompt, String userPrompt, LlmOptions options);

    /**
     * Detailed single-turn generation with real token counters and durations
     * when the provider exposes them. The default implementation delegates to
     * {@link #chat} and approximates tokens by length, so simple providers and
     * test fakes stay valid.
     */
    default LlmResult generate(String systemPrompt, String userPrompt, LlmOptions options) {
        long start = System.nanoTime();
        String content = chat(systemPrompt, userPrompt, options);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return LlmResult.estimated(content, estimateTokens(systemPrompt) + estimateTokens(userPrompt),
                estimateTokens(content), elapsedMs);
    }

    /** Embeds a batch of texts, returning L2-normalized vectors. */
    List<float[]> embed(List<String> texts);

    /** Cheap reachability check used for health reporting. */
    boolean isAvailable();

    /** Default chat model configured for this deployment. */
    String defaultChatModel();

    /**
     * Currently installed models. Providers that cannot enumerate models return
     * an empty list.
     */
    default List<LlmModelInfo> listModels() {
        return List.of();
    }

    /** Conservative token approximation (~4 characters per token). */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }
}
