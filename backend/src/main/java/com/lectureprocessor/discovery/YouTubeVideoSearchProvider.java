package com.lectureprocessor.discovery;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.credential.ProviderCredentialService;
import com.lectureprocessor.process.CancellationToken;
import com.lectureprocessor.process.ProcessExecutor;
import com.lectureprocessor.process.ProcessResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * YouTube search.
 *
 * <p>Strategy, in order of preference:</p>
 * <ol>
 *   <li>official YouTube Data API v3 when an API key is configured in
 *       {@code /settings} (quota-limited, documented);</li>
 *   <li>the supported yt-dlp search mechanism ({@code ytsearchN:}) — it returns
 *       real YouTube metadata without downloading and without an API key.</li>
 * </ol>
 *
 * <p>No HTML scraping is used.</p>
 */
@Component
public class YouTubeVideoSearchProvider implements VideoSearchProvider {

    public static final String API_KEY_ID = "youtube";
    private static final Logger log = LoggerFactory.getLogger(YouTubeVideoSearchProvider.class);

    private final DiscoveryHttpClient http;
    private final ProviderCredentialService credentials;
    private final DiscoveryProperties properties;
    private final ProcessExecutor processes;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public YouTubeVideoSearchProvider(DiscoveryHttpClient http,
            ProviderCredentialService credentials, DiscoveryProperties properties,
            ProcessExecutor processes, AppProperties appProperties, ObjectMapper objectMapper) {
        this.http = http;
        this.credentials = credentials;
        this.properties = properties;
        this.processes = processes;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return "youtube";
    }

    @Override
    public String displayName() {
        return "YouTube";
    }

    @Override
    public boolean available() {
        return credentials.isConfigured(API_KEY_ID) || ytDlpConfigured();
    }

    @Override
    public String availabilityMessage() {
        return available() ? "Доступен метод: " + method()
                : "Нет API key YouTube и не настроен yt-dlp";
    }

    @Override
    public String method() {
        return credentials.isConfigured(API_KEY_ID) ? "YOUTUBE_DATA_API" : "YT_DLP_SEARCH";
    }

    @Override
    public SearchOutcome search(VideoSearchQuery query, CancellationToken cancellation) {
        try {
            if (credentials.isConfigured(API_KEY_ID)) {
                return searchApi(query);
            }
            return searchYtDlp(query, cancellation);
        } catch (DiscoveryHttpException e) {
            return SearchOutcome.error(method(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("YouTube search failed: {}", e.getMessage());
            return SearchOutcome.error(method(), "Поиск YouTube недоступен: " + e.getMessage());
        }
    }

    private SearchOutcome searchApi(VideoSearchQuery query) {
        String key = credentials.resolveKey(API_KEY_ID)
                .orElseThrow(() -> new DiscoveryHttpException("YouTube API key не настроен"));
        String base = properties.youtubeApiBase();
        String searchUrl = base + "/search?part=snippet&type=video&maxResults="
                + Math.max(1, Math.min(query.limit(), 50)) + "&q=" + DiscoveryHttpClient.encode(query.query())
                + "&key=" + DiscoveryHttpClient.encode(key)
                + (query.language() == null ? "" : "&relevanceLanguage="
                        + DiscoveryHttpClient.encode(query.language()));
        JsonNode root = http.get(searchUrl, java.util.Map.of());
        List<String> ids = new ArrayList<>();
        List<DiscoveredVideo> videos = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            String id = item.path("id").path("videoId").asText(null);
            if (id == null || id.isBlank()) {
                continue;
            }
            ids.add(id);
            JsonNode snippet = item.path("snippet");
            videos.add(new DiscoveredVideo(0, id(), id,
                    "https://www.youtube.com/watch?v=" + id,
                    snippet.path("title").asText(""), snippet.path("description").asText(""),
                    snippet.path("channelTitle").asText(null), null, query.language(),
                    parseInstant(snippet.path("publishedAt").asText(null)),
                    thumbnail(snippet), query.query(), null, "TITLE_DESCRIPTION_EMBEDDING", null));
        }
        if (!ids.isEmpty()) {
            String detailsUrl = base + "/videos?part=contentDetails&id="
                    + DiscoveryHttpClient.encode(String.join(",", ids)) + "&key="
                    + DiscoveryHttpClient.encode(key);
            JsonNode details = http.get(detailsUrl, java.util.Map.of());
            java.util.Map<String, Long> durations = new java.util.HashMap<>();
            for (JsonNode item : details.path("items")) {
                durations.put(item.path("id").asText(""),
                        parseIsoDuration(item.path("contentDetails").path("duration").asText(null)));
            }
            videos = videos.stream().map(v -> new DiscoveredVideo(v.id(), v.provider(),
                    v.externalId(), v.canonicalUrl(), v.title(), v.description(), v.channel(),
                    durations.get(v.externalId()), v.language(), v.publishedAt(),
                    v.thumbnailUrl(), v.query(), v.relevance(), v.relevanceBasis(),
                    v.importedLectureId())).toList();
        }
        return SearchOutcome.ok(videos, "YOUTUBE_DATA_API");
    }

    private SearchOutcome searchYtDlp(VideoSearchQuery query, CancellationToken cancellation) {
        if (!ytDlpConfigured()) {
            return SearchOutcome.error("YT_DLP_SEARCH", "yt-dlp не настроен");
        }
        List<String> command = List.of(appProperties.downloader().ytDlpPath(),
                "--flat-playlist", "--dump-json", "--no-warnings", "--ignore-errors",
                "ytsearch" + Math.max(1, Math.min(query.limit(), 30)) + ":" + query.query());
        ProcessResult result = processes.execute(command,
                appProperties.downloader().metadataTimeout(), null);
        if (!result.isSuccess()) {
            return SearchOutcome.error("YT_DLP_SEARCH",
                    "yt-dlp search завершился с ошибкой: " + firstLine(result.outputForError()));
        }
        List<DiscoveredVideo> videos = new ArrayList<>();
        for (String line : result.stdout().split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(line);
                String id = node.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                String url = node.path("webpage_url").asText(
                        node.path("url").asText("https://www.youtube.com/watch?v=" + id));
                Long durationMs = node.path("duration").isNumber()
                        ? Math.round(node.path("duration").asDouble() * 1000) : null;
                videos.add(new DiscoveredVideo(0, id(), id, url,
                        node.path("title").asText(""), node.path("description").asText(""),
                        node.path("uploader").asText(node.path("channel").asText(null)),
                        durationMs, query.language(), null, null, query.query(), null,
                        "TITLE_DESCRIPTION_EMBEDDING", null));
            } catch (RuntimeException e) {
                log.debug("Skipping malformed yt-dlp search line");
            }
        }
        return SearchOutcome.ok(videos.stream().limit(query.limit()).toList(), "YT_DLP_SEARCH");
    }

    private boolean ytDlpConfigured() {
        String path = appProperties.downloader().ytDlpPath();
        return path != null && !path.isBlank();
    }

    private static String thumbnail(JsonNode snippet) {
        JsonNode thumbnails = snippet.path("thumbnails");
        if (thumbnails.isArray() && !thumbnails.isEmpty()) {
            return thumbnails.get(thumbnails.size() - 1).path("url").asText(null);
        }
        return null;
    }

    static Long parseIsoDuration(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.parse(value).toMillis();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String firstLine(String value) {
        if (value == null) {
            return "";
        }
        String[] lines = value.strip().split("\\R");
        return lines.length == 0 ? "" : lines[0];
    }
}
