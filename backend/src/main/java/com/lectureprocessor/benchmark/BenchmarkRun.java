package com.lectureprocessor.benchmark;

import java.time.Instant;

/**
 * Persisted outcome of one benchmark run. Every field that cannot be measured is
 * {@code null} rather than fabricated; {@link #status} records the lifecycle.
 */
public record BenchmarkRun(
        Long id,
        String lectureId,
        String provider,
        String model,
        Double temperature,
        Integer maxTokens,
        Integer contextWindow,
        Boolean thinking,
        String promptTemplate,
        String quantization,
        Integer inputTokens,
        Integer outputTokens,
        Integer thinkingTokens,
        Integer llmCalls,
        Integer chunkCount,
        Long llmDurationMs,
        Long totalDurationMs,
        Double tokensPerSecond,
        Long peakRamMb,
        Long peakVramMb,
        Double qualityScore,
        String qualityNotes,
        String status,
        String error,
        String output,
        boolean recommended,
        Instant createdAt) {

    public BenchmarkRun withId(long newId) {
        return new BenchmarkRun(newId, lectureId, provider, model, temperature, maxTokens, contextWindow,
                thinking, promptTemplate, quantization, inputTokens, outputTokens, thinkingTokens, llmCalls,
                chunkCount, llmDurationMs, totalDurationMs, tokensPerSecond, peakRamMb, peakVramMb,
                qualityScore, qualityNotes, status, error, output, recommended, createdAt);
    }

    public BenchmarkRun withStatus(String newStatus) {
        return new BenchmarkRun(id, lectureId, provider, model, temperature, maxTokens, contextWindow,
                thinking, promptTemplate, quantization, inputTokens, outputTokens, thinkingTokens, llmCalls,
                chunkCount, llmDurationMs, totalDurationMs, tokensPerSecond, peakRamMb, peakVramMb,
                qualityScore, qualityNotes, newStatus, error, output, recommended, createdAt);
    }

    public BenchmarkRun withResult(Integer newInputTokens, Integer newOutputTokens, Integer newThinkingTokens,
            Integer newLlmCalls, Integer newChunkCount, Long newLlmDurationMs, Double newTokensPerSecond,
            Long newPeakRamMb, Long newPeakVramMb, Double newQualityScore, String newQualityNotes,
            String newStatus, String newError, String newOutput) {
        return new BenchmarkRun(id, lectureId, provider, model, temperature, maxTokens, contextWindow,
                thinking, promptTemplate, quantization, newInputTokens, newOutputTokens, newThinkingTokens,
                newLlmCalls, newChunkCount, newLlmDurationMs, totalDurationMs, newTokensPerSecond,
                newPeakRamMb, newPeakVramMb, newQualityScore, newQualityNotes, newStatus, newError,
                newOutput, recommended, createdAt);
    }

    public BenchmarkRun withTotalDuration(Long newTotalDurationMs) {
        return new BenchmarkRun(id, lectureId, provider, model, temperature, maxTokens, contextWindow,
                thinking, promptTemplate, quantization, inputTokens, outputTokens, thinkingTokens,
                llmCalls, chunkCount, llmDurationMs, newTotalDurationMs, tokensPerSecond,
                peakRamMb, peakVramMb, qualityScore, qualityNotes, status, error,
                output, recommended, createdAt);
    }
}
