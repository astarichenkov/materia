package com.lectureprocessor.discovery;

import java.time.Instant;

/**
 * A normalized video returned by a real search provider. Duration, language and
 * publication date are nullable: a provider that does not report them must not
 * have values invented by the application.
 */
public record DiscoveredVideo(
        long id,
        String provider,
        String externalId,
        String canonicalUrl,
        String title,
        String description,
        String channel,
        Long durationMs,
        String language,
        Instant publishedAt,
        String thumbnailUrl,
        String query,
        Double relevance,
        String relevanceBasis,
        String importedLectureId) {

    public DiscoveredVideo withRelevance(double score, String basis) {
        return new DiscoveredVideo(id, provider, externalId, canonicalUrl, title, description,
                channel, durationMs, language, publishedAt, thumbnailUrl, query, score, basis,
                importedLectureId);
    }

    public DiscoveredVideo withImportedLecture(String lectureId) {
        return new DiscoveredVideo(id, provider, externalId, canonicalUrl, title, description,
                channel, durationMs, language, publishedAt, thumbnailUrl, query, relevance,
                relevanceBasis, lectureId);
    }

    public DiscoveredVideo withId(long newId) {
        return new DiscoveredVideo(newId, provider, externalId, canonicalUrl, title, description,
                channel, durationMs, language, publishedAt, thumbnailUrl, query, relevance,
                relevanceBasis, importedLectureId);
    }

    public String dedupeKey() {
        return provider + ":" + (externalId == null || externalId.isBlank()
                ? canonicalUrl : externalId);
    }
}
