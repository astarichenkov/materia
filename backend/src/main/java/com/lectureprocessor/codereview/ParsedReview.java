package com.lectureprocessor.codereview;

import java.util.List;

/** Validated review content that is safe to publish. */
public record ParsedReview(
        String summary,
        String overallRisk,
        List<ReviewFinding> findings,
        List<String> recommendations) {

    public ParsedReview {
        findings = findings == null ? List.of() : List.copyOf(findings);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }
}
