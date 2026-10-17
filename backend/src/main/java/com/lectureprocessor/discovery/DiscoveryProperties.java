package com.lectureprocessor.discovery;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for smart video discovery. Non-secret only; provider API keys
 * are stored encrypted through {@code ProviderCredentialService}.
 *
 * @param enabled              master switch for the discovery surface
 * @param cacheTtl             how long a successful provider search stays valid
 * @param errorCacheTtl        shorter TTL for failed searches
 * @param maxResultsPerProvider cap on raw provider results per query
 * @param maxResults           cap on final ranked results
 * @param maxQueries           number of topical queries generated per lecture
 * @param similarityTopK       local similar lectures returned
 * @param minSimilarity        minimum cosine similarity for local results
 * @param minOnlineRelevance   minimum combined relevance for online results
 * @param keywordBonusWeight   capped keyword-overlap bonus (never dominates)
 * @param providers            enabled provider ids
 * @param requestTimeout       per-request timeout for provider HTTP calls
 * @param maxResponseBytes     hard cap on a provider response body
 * @param youtubeApiBase       YouTube Data API base URL
 * @param vkApiBase            VK API base URL
 * @param rutubeApiBase        Rutube public search API base URL
 * @param excludePrivateQueryTerms remove personal-data-like tokens from queries
 */
@ConfigurationProperties(prefix = "app.discovery")
public record DiscoveryProperties(
        boolean enabled,
        Duration cacheTtl,
        Duration errorCacheTtl,
        int maxResultsPerProvider,
        int maxResults,
        int maxQueries,
        int similarityTopK,
        double minSimilarity,
        double minOnlineRelevance,
        double keywordBonusWeight,
        List<String> providers,
        Duration requestTimeout,
        int maxResponseBytes,
        String youtubeApiBase,
        String vkApiBase,
        String rutubeApiBase,
        boolean excludePrivateQueryTerms) {

    public static DiscoveryProperties defaults() {
        return new DiscoveryProperties(true, Duration.ofHours(24), Duration.ofMinutes(10), 15, 20,
                3, 8, 0.25, 0.20, 0.10,
                List.of("youtube", "vkontakte", "rutube"), Duration.ofSeconds(20), 2_000_000,
                "https://www.googleapis.com/youtube/v3", "https://api.vk.com/method",
                "https://rutube.ru/api", true);
    }
}
