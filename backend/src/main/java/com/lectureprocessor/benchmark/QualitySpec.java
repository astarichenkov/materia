package com.lectureprocessor.benchmark;

import java.util.List;

/**
 * Deterministic quality specification for a demo fixture: facts that must be
 * preserved, terms that indicate hallucination, and numbers that should appear.
 */
public record QualitySpec(
        List<String> requiredFacts,
        List<String> forbiddenTerms,
        List<String> requiredNumbers) {

    public QualitySpec {
        requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
        forbiddenTerms = forbiddenTerms == null ? List.of() : List.copyOf(forbiddenTerms);
        requiredNumbers = requiredNumbers == null ? List.of() : List.copyOf(requiredNumbers);
    }

    public static QualitySpec empty() {
        return new QualitySpec(List.of(), List.of(), List.of());
    }
}
