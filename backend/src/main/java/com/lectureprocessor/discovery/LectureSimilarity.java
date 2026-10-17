package com.lectureprocessor.discovery;

import java.time.Instant;
import java.util.List;

/** Ranked local recommendation persisted for the lecture UI. */
public record LectureSimilarity(
        String lectureId,
        String similarLectureId,
        double score,
        String basis,
        List<String> commonTopics,
        Instant computedAt) {

    public LectureSimilarity {
        commonTopics = commonTopics == null ? List.of() : List.copyOf(commonTopics);
    }
}
