package com.lectureprocessor.codereview;

import java.util.List;

/** Raw model output before validation. */
public record RawReview(
        String summary,
        String overallRisk,
        List<RawFinding> findings,
        List<String> recommendations) {

    public RawReview {
        findings = findings == null ? List.of() : List.copyOf(findings);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }
}
