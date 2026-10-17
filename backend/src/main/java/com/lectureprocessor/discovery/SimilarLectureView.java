package com.lectureprocessor.discovery;

import java.util.List;

/**
 * One local recommendation. Score is an actual cosine similarity (or a lexical
 * overlap when embeddings are unavailable) — never an LLM-generated percentage.
 */
public record SimilarLectureView(
        String lectureId,
        String title,
        String platform,
        double score,
        String basis,
        List<String> commonTopics,
        String language,
        Long durationMs,
        String status) {

    public SimilarLectureView {
        commonTopics = commonTopics == null ? List.of() : List.copyOf(commonTopics);
    }

    /** Display as a percentage only because the score is a calibrated cosine. */
    public int scorePercent() {
        return (int) Math.round(Math.max(0, Math.min(1, score)) * 100);
    }
}
