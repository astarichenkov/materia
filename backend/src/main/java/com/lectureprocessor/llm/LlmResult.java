package com.lectureprocessor.llm;

/**
 * Detailed result of one generation request. Token counters and durations are
 * real provider measurements when available; {@link #estimated()} marks values
 * that fell back to a character-based approximation.
 *
 * @param content        generated answer (thinking stripped by the provider)
 * @param inputTokens    prompt tokens ({@code prompt_eval_count})
 * @param outputTokens   generated tokens, including thinking ({@code eval_count})
 * @param thinkingTokens best-effort count of reasoning tokens (0 when unused)
 * @param durationMs     wall time of the provider call
 * @param evalTokensPerSecond measured generation speed ({@code eval_count / eval_duration})
 * @param loadMs         model load time reported by the provider (0 if warm)
 * @param estimated      true when token counters are approximated, not measured
 */
public record LlmResult(
        String content,
        int inputTokens,
        int outputTokens,
        int thinkingTokens,
        long durationMs,
        double evalTokensPerSecond,
        long loadMs,
        boolean estimated,
        boolean truncated) {

    public LlmResult(String content, int inputTokens, int outputTokens, int thinkingTokens, long durationMs,
            double evalTokensPerSecond, long loadMs, boolean estimated) {
        this(content, inputTokens, outputTokens, thinkingTokens, durationMs, evalTokensPerSecond, loadMs,
                estimated, false);
    }

    public static LlmResult estimated(String content, int inputTokens, int outputTokens, long durationMs) {
        double tps = durationMs > 0 ? outputTokens / (durationMs / 1000.0) : 0;
        return new LlmResult(content, inputTokens, outputTokens, 0, durationMs, tps, 0, true, false);
    }
}
