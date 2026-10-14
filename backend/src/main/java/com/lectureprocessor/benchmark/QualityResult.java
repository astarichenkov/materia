package com.lectureprocessor.benchmark;

import java.util.List;

/**
 * Deterministic quality score (0–5) plus human-readable notes. LLM-as-judge is
 * deliberately not the only signal; these checks are reproducible.
 */
public record QualityResult(double score, List<String> notes) {

    public QualityResult {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public String notesText() {
        return String.join("; ", notes);
    }
}
