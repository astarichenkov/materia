package com.lectureprocessor.discovery;

import java.time.Instant;

/**
 * One provider query after query generation and sanitization.
 *
 * @param query               short topical search phrase (no personal data)
 * @param language            optional language hint
 * @param minDurationSeconds  optional minimum duration
 * @param maxDurationSeconds  optional maximum duration
 * @param publishedAfter      optional earliest publication date
 * @param limit               provider result cap
 */
public record VideoSearchQuery(
        String query,
        String language,
        Integer minDurationSeconds,
        Integer maxDurationSeconds,
        Instant publishedAfter,
        int limit) {

    public String filterKey() {
        return language + "|" + minDurationSeconds + "|" + maxDurationSeconds + "|"
                + (publishedAfter == null ? "" : publishedAfter) + "|" + limit;
    }
}
