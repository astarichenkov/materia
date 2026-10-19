package com.lectureprocessor.discovery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.job.LectureJobService;
import com.lectureprocessor.lecture.RemoteLectureService;
import com.lectureprocessor.llm.EmbeddingProvider;
import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.process.CancellationToken;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.MediaSourceRepository;
import com.lectureprocessor.util.VectorMath;

/**
 * Online video discovery: query generation, provider search with cache,
 * normalization, deduplication, filtering, two-stage relevance ranking and
 * one-click import through the existing ingestion pipeline.
 *
 * <p>Relevance for online results is computed from title/description only and
 * the response labels the basis explicitly — the system never claims to have
 * analysed a video transcript that it did not download.</p>
 */
@Service
public class VideoDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(VideoDiscoveryService.class);
    private static final int MAX_QUERY_CHARS = 120;

    private final VideoProviderRegistry providers;
    private final DiscoveryProperties properties;
    private final DiscoveryCacheRepository cache;
    private final DiscoveredVideoRepository discovered;
    private final LectureRepository lectures;
    private final MediaSourceRepository mediaSources;
    private final TopicExtractionService topics;
    private final EmbeddingProvider embeddings;
    private final RemoteLectureService remoteLectures;
    private final LectureJobService jobs;

    public VideoDiscoveryService(VideoProviderRegistry providers, DiscoveryProperties properties,
            DiscoveryCacheRepository cache, DiscoveredVideoRepository discovered,
            LectureRepository lectures, MediaSourceRepository mediaSources,
            TopicExtractionService topics, EmbeddingProvider embeddings,
            RemoteLectureService remoteLectures, LectureJobService jobs) {
        this.providers = providers;
        this.properties = properties;
        this.cache = cache;
        this.discovered = discovered;
        this.lectures = lectures;
        this.mediaSources = mediaSources;
        this.topics = topics;
        this.embeddings = embeddings;
        this.remoteLectures = remoteLectures;
        this.jobs = jobs;
    }

    public DiscoverySearchResponse search(DiscoverySearchRequest request) {
        if (!properties.enabled()) {
            return new DiscoverySearchResponse(null, List.of(), List.of(),
                    List.of("Поиск видео отключён (app.discovery.enabled=false)"),
                    providerStatuses(List.of()), "NONE", false);
        }
        TopicContext context = topicContext(request);
        List<String> queries = queries(context, request.query());
        if (queries.isEmpty()) {
            return new DiscoverySearchResponse(null, List.of(), List.of(),
                    List.of("Не удалось сформировать поисковый запрос: укажите тему или обработайте материал"),
                    providerStatuses(List.of()), "NONE", false);
        }
        List<VideoSearchProvider> selected = selectedProviders(request.platform());
        List<String> warnings = new ArrayList<>();
        List<ProviderError> errors = new ArrayList<>();
        Map<String, DiscoveredVideo> unique = new LinkedHashMap<>();
        Set<String> contentKeys = new LinkedHashSet<>();
        List<VideoSearchProvider> used = new ArrayList<>();
        boolean anyCache = false;

        for (String query : queries) {
            VideoSearchQuery searchQuery = new VideoSearchQuery(query, request.languageOrNull(),
                    request.minDurationSecondsOrNull(), request.maxDurationSecondsOrNull(),
                    request.publishedAfterOrNull(), Math.max(1, Math.min(
                            request.maxResultsOrDefault(properties.maxResults()),
                            properties.maxResultsPerProvider())));
            for (VideoSearchProvider provider : selected) {
                if (!provider.available()) {
                    warnings.add(provider.displayName() + ": " + provider.availabilityMessage());
                    errors.add(new ProviderError(provider.id(), provider.availabilityMessage()));
                    continue;
                }
                if (!used.contains(provider)) {
                    used.add(provider);
                }
                VideoSearchProvider.SearchOutcome outcome;
                Optional<DiscoveryCacheRepository.CachedSearch> cached =
                        cache.findFresh(provider.id(), query, searchQuery.filterKey());
                if (cached.isPresent()) {
                    anyCache = true;
                    outcome = cached.get().toOutcome(provider.method());
                } else {
                    outcome = provider.search(searchQuery, new CancellationToken());
                    cache.store(provider.id(), query, searchQuery.filterKey(), outcome,
                            Instant.now().plus(outcome.success()
                                    ? properties.cacheTtl() : properties.errorCacheTtl()));
                }
                if (!outcome.success()) {
                    warnings.add(provider.displayName() + " (" + query + "): " + outcome.error());
                    errors.add(new ProviderError(provider.id(), outcome.error()));
                    continue;
                }
                for (DiscoveredVideo video : outcome.videos()) {
                    if (excluded(video, context)) {
                        continue;
                    }
                    // Provider ids catch exact duplicates; title+duration catches
                    // the same video mirrored on another platform.
                    if (!contentKeys.add(contentKey(video))) {
                        continue;
                    }
                    unique.putIfAbsent(video.dedupeKey(), video);
                }
            }
        }

        List<DiscoveredVideo> filtered = unique.values().stream()
                .filter(v -> matches(v, request))
                .toList();
        List<DiscoveredVideo> ranked = rank(filtered, context,
                context.topic() != null ? context.topic().profileText() : queries.getFirst());
        List<DiscoveredVideo> limited = ranked.stream()
                .limit(request.maxResultsOrDefault(properties.maxResults())).toList();
        List<DiscoveredVideo> stored = limited.stream().map(this::store).toList();
        String basis = limited.isEmpty() ? "NONE" : limited.getFirst().relevanceBasis();
        return new DiscoverySearchResponse(queries.getFirst(), queries, stored, warnings,
                providerStatuses(errors), basis, anyCache && !limited.isEmpty());
    }

    /** Import a discovered video through the existing source ingestion. */
    public ImportResult importVideo(ImportRequest request) {
        VideoSearchProvider provider = providers.byId(request.provider())
                .orElseThrow(() -> new IllegalArgumentException("Неизвестный провайдер: "
                        + request.provider()));
        String url = request.url();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Не задан URL видео");
        }
        Optional<String> existing = existingLecture(url);
        if (existing.isPresent()) {
            discovered.findByProviderAndExternalId(provider.id(), request.externalId())
                    .ifPresent(v -> discovered.markImported(v.provider(), v.externalId(),
                            existing.get()));
            return new ImportResult(existing.get(), false, false);
        }
        Lecture lecture = remoteLectures.createFromUrl(url, request.qualityOrDefault(),
                false, request.cleanOrDefault(), request.slidesOrDefault());
        boolean submitted = jobs.submit(lecture.id());
        discovered.findByProviderAndExternalId(provider.id(), request.externalId())
                .ifPresentOrElse(v -> discovered.markImported(v.provider(), v.externalId(),
                        lecture.id()), () -> log.debug("Imported video was not cached"));
        return new ImportResult(lecture.id(), true, submitted);
    }

    public List<ProviderStatus> providerStatuses() {
        return providerStatuses(List.of());
    }

    public List<DiscoveredVideo> recent() {
        return discovered.findRecent(25);
    }

    private TopicContext topicContext(DiscoverySearchRequest request) {
        if (request.lectureId() == null || request.lectureId().isBlank()) {
            return new TopicContext(null, null, null, null);
        }
        Lecture lecture = lectures.findById(request.lectureId())
                .orElseThrow(() -> new IllegalArgumentException("Материал не найден: "
                        + request.lectureId()));
        LectureTopic topic = topics.ensure(lecture.id());
        MediaSource source = mediaSources.findByLecture(lecture.id()).orElse(null);
        return new TopicContext(lecture, topic, sourceUrl(source), externalId(source));
    }

    private List<String> queries(TopicContext context, String explicit) {
        Set<String> result = new LinkedHashSet<>();
        if (explicit != null && !explicit.isBlank()) {
            String cleaned = sanitize(explicit);
            if (!cleaned.isBlank()) {
                result.add(cleaned);
            }
        } else if (context.topic() != null) {
            LectureTopic topic = context.topic();
            String title = sanitize(topic.title());
            if (!title.isBlank()) {
                result.add(truncate(title, MAX_QUERY_CHARS));
            }
            for (String keyTopic : topic.keyTopics()) {
                String cleaned = sanitize(keyTopic);
                if (cleaned.length() >= 3 && result.size() < properties.maxQueries()) {
                    result.add(truncate(cleaned, MAX_QUERY_CHARS));
                }
            }
            if (result.size() < properties.maxQueries() && !topic.keywords().isEmpty()) {
                String keywordQuery = String.join(" ",
                        topic.keywords().subList(0, Math.min(5, topic.keywords().size())));
                result.add(truncate(sanitize(keywordQuery), MAX_QUERY_CHARS));
            }
        }
        return result.stream().filter(q -> !q.isBlank()).limit(properties.maxQueries()).toList();
    }

    /** Removes URLs, e-mail addresses, phone numbers and long IDs from queries. */
    public String sanitize(String query) {
        if (query == null) {
            return "";
        }
        String value = query;
        if (properties.excludePrivateQueryTerms()) {
            value = value.replaceAll("https?://\\S+", " ")
                    .replaceAll("\\S+@\\S+", " ")
                    .replaceAll("\\+?\\d[\\d\\s()-]{8,}\\d", " ")
                    .replaceAll("\\b\\d{7,}\\b", " ");
        }
        return value.replaceAll("\\s+", " ").strip();
    }

    private List<VideoSearchProvider> selectedProviders(String platform) {
        if (platform != null && !platform.isBlank()) {
            return providers.byId(platform).map(List::of).orElse(List.of());
        }
        return providers.enabled();
    }

    private boolean excluded(DiscoveredVideo video, TopicContext context) {
        if (context.externalId() != null && context.externalId().equals(video.externalId())) {
            return true;
        }
        if (context.sourceUrl() != null && LocalSimilarityService.canonical(context.sourceUrl())
                .equals(LocalSimilarityService.canonical(video.canonicalUrl()))) {
            return true;
        }
        // Already imported videos are not suggested as new.
        return existingLecture(video.canonicalUrl()).isPresent();
    }

    private boolean matches(DiscoveredVideo video, DiscoverySearchRequest request) {
        if (request.platform() != null && !request.platform().isBlank()
                && !request.platform().equals(video.provider())) {
            return false;
        }
        if (request.language() != null && !request.language().isBlank() && video.language() != null
                && !request.language().equalsIgnoreCase(video.language())) {
            return false;
        }
        if (request.minDurationSecondsOrNull() != null) {
            if (video.durationMs() == null || video.durationMs() < request.minDurationSecondsOrNull() * 1000L) {
                return false;
            }
        }
        if (request.maxDurationSecondsOrNull() != null) {
            if (video.durationMs() == null || video.durationMs() > request.maxDurationSecondsOrNull() * 1000L) {
                return false;
            }
        }
        if (request.publishedAfterOrNull() != null && video.publishedAt() != null
                && video.publishedAt().isBefore(request.publishedAfterOrNull())) {
            return false;
        }
        return true;
    }

    private List<DiscoveredVideo> rank(List<DiscoveredVideo> videos, TopicContext context,
            String profileText) {
        if (videos.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = null;
        try {
            if (embeddings.isAvailable()) {
                List<String> texts = new ArrayList<>();
                texts.add(profileText);
                videos.forEach(v -> texts.add(videoText(v)));
                vectors = embeddings.embed(texts);
            }
        } catch (RuntimeException e) {
            log.warn("Online ranking embeddings unavailable, using lexical similarity: {}",
                    e.getMessage());
        }
        Set<String> profileWords = TopicExtractionService.wordSet(profileText);
        double bonusWeight = Math.max(0, properties.keywordBonusWeight());
        List<DiscoveredVideo> ranked = new ArrayList<>();
        for (int i = 0; i < videos.size(); i++) {
            DiscoveredVideo video = videos.get(i);
            double similarity = 0;
            String basis;
            if (vectors != null && vectors.size() == videos.size() + 1) {
                similarity = VectorMath.cosine(vectors.getFirst(), vectors.get(i + 1));
                basis = "EMBEDDING_TITLE_DESCRIPTION";
            } else {
                similarity = TopicExtractionService.jaccard(profileWords,
                        TopicExtractionService.wordSet(videoText(video)));
                basis = "LEXICAL_TITLE_DESCRIPTION";
            }
            long keywordOverlap = profileWords.stream()
                    .filter(w -> videoText(video).toLowerCase(Locale.ROOT).contains(w))
                    .count();
            double bonus = Math.min(bonusWeight, keywordOverlap * 0.02);
            double score = Math.max(0, Math.min(1, similarity + bonus));
            if (score < properties.minOnlineRelevance()) {
                continue;
            }
            ranked.add(video.withRelevance(score, basis));
        }
        ranked.sort(Comparator.comparingDouble(DiscoveredVideo::relevance).reversed());
        return ranked;
    }

    private DiscoveredVideo store(DiscoveredVideo video) {
        Optional<String> existing = existingLecture(video.canonicalUrl());
        DiscoveredVideo prepared = existing.map(video::withImportedLecture).orElse(video);
        return discovered.upsert(prepared);
    }

    private Optional<String> existingLecture(String url) {
        String canonical = LocalSimilarityService.canonical(url);
        if (canonical.isBlank()) {
            return Optional.empty();
        }
        return mediaSources.findAll().stream()
                .filter(s -> s.originalUrl() != null
                        && canonical.equals(LocalSimilarityService.canonical(s.originalUrl())))
                .map(MediaSource::lectureId).findFirst();
    }

    private List<ProviderStatus> providerStatuses(List<ProviderError> errors) {
        Map<String, String> errorById = new HashMap<>();
        errors.forEach(e -> errorById.putIfAbsent(e.providerId(), e.error()));
        return providers.all().stream().map(p -> new ProviderStatus(p.id(), p.displayName(),
                p.available(), p.availabilityMessage(), p.method(),
                errorById.get(p.id()))).toList();
    }

    private static String contentKey(DiscoveredVideo video) {
        String title = video.title() == null ? "" : video.title().toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
        if (title.isBlank()) {
            return "url:" + LocalSimilarityService.canonical(video.canonicalUrl());
        }
        long seconds = video.durationMs() == null ? -1 : Math.round(video.durationMs() / 1000.0);
        return title + "|" + seconds;
    }

    private static String videoText(DiscoveredVideo video) {
        return (video.title() + " " + (video.description() == null ? "" : video.description()))
                .strip();
    }

    private static String sourceUrl(MediaSource source) {
        return source == null ? null : source.originalUrl();
    }

    private static String externalId(MediaSource source) {
        return source == null ? null : source.externalId();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max).strip();
    }

    private record TopicContext(Lecture lecture, LectureTopic topic, String sourceUrl,
            String externalId) {
    }

    private record ProviderError(String providerId, String error) {
    }

    /** Request DTO with nullable primitives (Jackson 3 forbids null primitives). */
    public record DiscoverySearchRequest(
            String lectureId,
            String query,
            String platform,
            String language,
            Integer minDurationSeconds,
            Integer maxDurationSeconds,
            Integer publishedWithinDays,
            Integer maxResults) {

        public String languageOrNull() {
            return language == null || language.isBlank() ? null : language;
        }

        public Integer minDurationSecondsOrNull() {
            return minDurationSeconds;
        }

        public Integer maxDurationSecondsOrNull() {
            return maxDurationSeconds;
        }

        public Integer maxResultsOrDefault(int fallback) {
            return maxResults == null || maxResults <= 0 ? fallback : maxResults;
        }

        public Instant publishedAfterOrNull() {
            return publishedWithinDays == null || publishedWithinDays <= 0 ? null
                    : Instant.now().minusSeconds(publishedWithinDays * 86_400L);
        }
    }

    public record DiscoverySearchResponse(
            String query,
            List<String> generatedQueries,
            List<DiscoveredVideo> results,
            List<String> warnings,
            List<ProviderStatus> providers,
            String relevanceBasis,
            boolean cached) {
    }

    public record ProviderStatus(String id, String displayName, boolean available, String message,
            String method, String lastError) {
    }

    public record ImportRequest(String provider, String externalId, String url, String quality,
            Boolean clean, Boolean slides) {

        public String qualityOrDefault() {
            return quality == null || quality.isBlank() ? "720p" : quality;
        }

        public boolean cleanOrDefault() {
            return Boolean.TRUE.equals(clean);
        }

        public boolean slidesOrDefault() {
            return Boolean.TRUE.equals(slides);
        }
    }

    public record ImportResult(String lectureId, boolean created, boolean submitted) {
    }
}
