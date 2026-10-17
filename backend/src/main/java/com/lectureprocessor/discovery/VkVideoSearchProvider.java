package com.lectureprocessor.discovery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lectureprocessor.llm.credential.ProviderCredentialService;
import com.lectureprocessor.process.CancellationToken;

import tools.jackson.databind.JsonNode;

/**
 * VK / VK Video search through the official VK API ({@code video.search}).
 *
 * <p>VK requires a user access token with video permissions; an application
 * service token does not work for search. Without a token the provider reports
 * itself unavailable — it never scrapes HTML and never fakes results.</p>
 */
@Component
public class VkVideoSearchProvider implements VideoSearchProvider {

    public static final String API_KEY_ID = "vk";
    private static final Logger log = LoggerFactory.getLogger(VkVideoSearchProvider.class);
    private static final String API_VERSION = "5.199";

    private final DiscoveryHttpClient http;
    private final ProviderCredentialService credentials;
    private final DiscoveryProperties properties;

    public VkVideoSearchProvider(DiscoveryHttpClient http, ProviderCredentialService credentials,
            DiscoveryProperties properties) {
        this.http = http;
        this.credentials = credentials;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "vkontakte";
    }

    @Override
    public String displayName() {
        return "VK / VK Видео";
    }

    @Override
    public boolean available() {
        return credentials.isConfigured(API_KEY_ID);
    }

    @Override
    public String availabilityMessage() {
        return available() ? "Доступен официальный VK API video.search"
                : "Нужен VK access token (video.search); без него поиск VK недоступен";
    }

    @Override
    public String method() {
        return "VK_API_VIDEO_SEARCH";
    }

    @Override
    public SearchOutcome search(VideoSearchQuery query, CancellationToken cancellation) {
        String token = credentials.resolveKey(API_KEY_ID).orElse(null);
        if (token == null) {
            return SearchOutcome.error(method(), availabilityMessage());
        }
        String url = properties.vkApiBase() + "/video.search?q="
                + DiscoveryHttpClient.encode(query.query()) + "&count="
                + Math.max(1, Math.min(query.limit(), 50)) + "&sort=2&access_token="
                + DiscoveryHttpClient.encode(token) + "&v=" + API_VERSION;
        try {
            JsonNode root = http.get(url, java.util.Map.of());
            JsonNode error = root.path("error");
            if (error.isObject()) {
                return SearchOutcome.error(method(),
                        "VK API error: " + error.path("error_msg").asText("unknown"));
            }
            List<DiscoveredVideo> videos = new ArrayList<>();
            for (JsonNode item : root.path("response").path("items")) {
                String id = item.path("id").asText(null);
                String owner = item.path("owner_id").asText(null);
                if (id == null || owner == null) {
                    continue;
                }
                Long durationMs = item.path("duration").isNumber()
                        ? item.path("duration").asLong() * 1000 : null;
                Instant published = item.path("date").isNumber()
                        ? Instant.ofEpochSecond(item.path("date").asLong()) : null;
                videos.add(new DiscoveredVideo(0, id(), owner + "_" + id,
                        "https://vk.com/video" + owner + "_" + id,
                        item.path("title").asText(""), item.path("description").asText(""),
                        null, durationMs, query.language(), published,
                        item.path("photo_800").asText(item.path("photo_320").asText(null)),
                        query.query(), null, "TITLE_DESCRIPTION_EMBEDDING", null));
            }
            return SearchOutcome.ok(videos, method());
        } catch (DiscoveryHttpException e) {
            return SearchOutcome.error(method(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("VK search failed: {}", e.getMessage());
            return SearchOutcome.error(method(), "Поиск VK недоступен: " + e.getMessage());
        }
    }
}
