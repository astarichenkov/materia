package com.lectureprocessor.discovery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lectureprocessor.process.CancellationToken;

import tools.jackson.databind.JsonNode;

/**
 * Rutube search through its public JSON search endpoint.
 *
 * <p>The endpoint is public but not part of a formally versioned partner API;
 * this limitation is documented. If it changes or blocks requests, the provider
 * returns an explicit error instead of cached/fabricated results. No HTML
 * parsing is used.</p>
 */
@Component
public class RutubeVideoSearchProvider implements VideoSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(RutubeVideoSearchProvider.class);

    private final DiscoveryHttpClient http;
    private final DiscoveryProperties properties;

    public RutubeVideoSearchProvider(DiscoveryHttpClient http, DiscoveryProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "rutube";
    }

    @Override
    public String displayName() {
        return "Rutube";
    }

    @Override
    public boolean available() {
        return properties.enabled();
    }

    @Override
    public String availabilityMessage() {
        return available() ? "Доступен публичный Rutube search API"
                : "Поиск Rutube отключён настройкой app.discovery.enabled";
    }

    @Override
    public String method() {
        return "RUTUBE_PUBLIC_API";
    }

    @Override
    public SearchOutcome search(VideoSearchQuery query, CancellationToken cancellation) {
        String url = properties.rutubeApiBase() + "/search/video/?query="
                + DiscoveryHttpClient.encode(query.query()) + "&limit="
                + Math.max(1, Math.min(query.limit(), 50));
        try {
            JsonNode root = http.get(url, java.util.Map.of());
            List<DiscoveredVideo> videos = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                String id = item.path("id").asText(null);
                String videoUrl = item.path("video_url").asText(null);
                if (id == null || videoUrl == null || videoUrl.isBlank()) {
                    continue;
                }
                Long durationMs = item.path("duration").isNumber()
                        ? item.path("duration").asLong() * 1000 : null;
                Instant published = item.path("created_ts").isNumber()
                        ? Instant.ofEpochSecond(item.path("created_ts").asLong()) : null;
                videos.add(new DiscoveredVideo(0, id(), id, videoUrl,
                        item.path("title").asText(""), item.path("description").asText(""),
                        item.path("author").path("name").asText(null), durationMs,
                        query.language(), published, item.path("thumbnail_url").asText(null),
                        query.query(), null, "TITLE_DESCRIPTION_EMBEDDING", null));
            }
            return SearchOutcome.ok(videos, method());
        } catch (DiscoveryHttpException e) {
            return SearchOutcome.error(method(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Rutube search failed: {}", e.getMessage());
            return SearchOutcome.error(method(), "Поиск Rutube недоступен: " + e.getMessage());
        }
    }
}
