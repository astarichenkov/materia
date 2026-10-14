package com.lectureprocessor.benchmark;

/**
 * Side-by-side comparison of two benchmark runs (baseline → optimized).
 * Deltas and speedups are computed from the persisted, measured values.
 */
public record BenchmarkComparison(
        BenchmarkRun baseline,
        BenchmarkRun optimized,
        double llmSpeedup,
        double totalSpeedup,
        double qualityDelta,
        Long ramDeltaMb,
        Long vramDeltaMb,
        String summary) {
}
