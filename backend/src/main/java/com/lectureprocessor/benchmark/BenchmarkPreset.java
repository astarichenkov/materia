package com.lectureprocessor.benchmark;

import com.lectureprocessor.llm.LlmRunConfig;

/**
 * A named starting point for the benchmark form. Custom is provided by the UI
 * (it simply does not override user-edited fields).
 */
public record BenchmarkPreset(
        String id,
        String label,
        String description,
        LlmRunConfig config) {
}
