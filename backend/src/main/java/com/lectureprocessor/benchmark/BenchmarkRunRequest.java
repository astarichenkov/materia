package com.lectureprocessor.benchmark;

import com.lectureprocessor.llm.LlmRunConfig;

/**
 * A request to run one benchmark. {@code fixtureKey} selects the deterministic
 * quality spec; {@code manualScore} optionally overrides the computed quality.
 */
public record BenchmarkRunRequest(
        String lectureId,
        String fixtureKey,
        LlmRunConfig config,
        Double manualScore) {
}
