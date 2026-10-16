package com.lectureprocessor.codereview;

import java.util.List;

/**
 * Structured outcome of one AI review, before and after publishing.
 */
public record ReviewResult(
        String summary,
        List<ReviewFinding> findings,
        List<String> recommendations,
        String overallRisk,
        String provider,
        String model,
        boolean fallback,
        boolean cloud,
        boolean partial,
        List<String> analyzedFiles,
        List<String> skippedFiles,
        int inputTokens,
        int outputTokens,
        int llmCalls,
        long llmMs,
        String error) {

    public ReviewResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        analyzedFiles = analyzedFiles == null ? List.of() : List.copyOf(analyzedFiles);
        skippedFiles = skippedFiles == null ? List.of() : List.copyOf(skippedFiles);
    }

    public boolean failed() {
        return error != null && !error.isBlank();
    }
}
