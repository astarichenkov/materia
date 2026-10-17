package com.lectureprocessor.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.job.LectureJobService;
import com.lectureprocessor.lecture.RemoteLectureService;
import com.lectureprocessor.llm.EmbeddingProvider;
import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.process.CancellationToken;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.MediaSourceRepository;
import com.lectureprocessor.support.FakeLlmProvider;

import tools.jackson.databind.ObjectMapper;

class VideoDiscoveryServiceTest {

    private VideoProviderRegistry registry;
    private DiscoveryProperties properties;
    private DiscoveryCacheRepository cache;
    private DiscoveredVideoRepository discovered;
    private LectureRepository lectures;
    private MediaSourceRepository mediaSources;
    private TopicExtractionService topics;
    private RemoteLectureService remoteLectures;
    private LectureJobService jobs;
    private VideoSearchProvider provider;
    private VideoDiscoveryService service;

    @BeforeEach
    void setUp() {
        properties = DiscoveryProperties.defaults();
        registry = mock(VideoProviderRegistry.class);
        cache = mock(DiscoveryCacheRepository.class);
        discovered = mock(DiscoveredVideoRepository.class);
        lectures = mock(LectureRepository.class);
        mediaSources = mock(MediaSourceRepository.class);
        topics = mock(TopicExtractionService.class);
        remoteLectures = mock(RemoteLectureService.class);
        jobs = mock(LectureJobService.class);
        provider = mock(VideoSearchProvider.class);
        EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
        when(registry.enabled()).thenReturn(List.of(provider));
        when(registry.all()).thenReturn(List.of(provider));
        when(registry.byId("youtube")).thenReturn(Optional.of(provider));
        when(provider.id()).thenReturn("youtube");
        when(provider.displayName()).thenReturn("YouTube");
        when(provider.available()).thenReturn(true);
        when(provider.availabilityMessage()).thenReturn("ok");
        when(provider.method()).thenReturn("TEST");
        when(cache.findFresh(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(discovered.upsert(any())).thenAnswer(i -> i.getArgument(0));
        when(embeddings.isAvailable()).thenReturn(true);
        when(embeddings.embed(any())).thenAnswer(i -> {
            List<String> texts = i.getArgument(0);
            return texts.stream().map(FakeLlmProvider::embedOne).toList();
        });
        service = new VideoDiscoveryService(registry, properties, cache, discovered, lectures,
                mediaSources, topics, embeddings, remoteLectures, jobs);
    }

    @Test
    void sanitizesPersonalDataFromQueries() {
        String sanitized = service.sanitize(
                "Иван Иванов ivan@example.com +7 999 123-45-67 https://secret.example/lecture "
                        + "machine learning 123456789");
        assertThat(sanitized).doesNotContain("ivan@example.com", "secret.example",
                "123456789", "+7 999");
        assertThat(sanitized).contains("machine learning");
    }

    @Test
    void excludesSourceAndDuplicatesAndRanksRelevantFirst() {
        String lectureId = "lecture-1";
        Lecture lecture = lecture(lectureId);
        when(lectures.findById(lectureId)).thenReturn(Optional.of(lecture));
        when(mediaSources.findByLecture(lectureId)).thenReturn(Optional.of(MediaSource.remote(
                lectureId, "https://www.youtube.com/watch?v=source", "720p", false)
                .withMetadata("YouTube", "source", "Source", null, 1000L, null, "sum")));
        when(mediaSources.findAll()).thenReturn(List.of());
        when(mediaSources.findByLecture("other")).thenReturn(Optional.empty());
        when(topics.ensure(lectureId)).thenReturn(new LectureTopic(lectureId, "Embeddings lecture",
                "Embeddings and retrieval augmented generation", List.of("embeddings", "retrieval"),
                List.of("embeddings", "retrieval", "machine", "learning"),
                "embeddings retrieval machine learning", FakeLlmProvider.embedOne(
                        "embeddings retrieval machine learning"), "hash", Instant.now()));

        when(provider.search(any(), any())).thenReturn(VideoSearchProvider.SearchOutcome.ok(List.of(
                video("source", "Source video", "embeddings retrieval machine learning"),
                video("dup", "Embeddings tutorial", "embeddings retrieval machine learning"),
                video("dup", "Embeddings tutorial", "embeddings retrieval machine learning"),
                video("irrelevant", "Cooking pasta", "italian food recipe")), "TEST"));

        var response = service.search(new VideoDiscoveryService.DiscoverySearchRequest(lectureId,
                null, null, null, null, null, null, 10));

        assertThat(response.results()).extracting(DiscoveredVideo::title)
                .doesNotContain("Source video");
        assertThat(response.results()).extracting(DiscoveredVideo::externalId)
                .doesNotHaveDuplicates().doesNotContain("source");
        assertThat(response.results().getFirst().title()).isEqualTo("Embeddings tutorial");
        assertThat(response.results().getFirst().relevanceBasis())
                .contains("TITLE_DESCRIPTION");
        assertThat(response.results()).allSatisfy(v -> assertThat(v.relevance())
                .isBetween(0.0, 1.0));
    }

    @Test
    void cachesProviderSearchesAndReportsDisabledProvider() {
        when(provider.available()).thenReturn(false);
        when(provider.availabilityMessage()).thenReturn("Нет токена");
        when(provider.search(any(), any())).thenReturn(VideoSearchProvider.SearchOutcome.ok(
                List.of(), "TEST"));
        when(mediaSources.findAll()).thenReturn(List.of());

        var response = service.search(new VideoDiscoveryService.DiscoverySearchRequest(null,
                "embeddings", null, null, null, null, null, 5));

        assertThat(response.results()).isEmpty();
        assertThat(response.warnings()).anyMatch(w -> w.contains("Нет токена"));
        verify(provider, never()).search(any(), any());
        assertThat(response.providers().getFirst().lastError()).contains("Нет токена");
    }

    @Test
    void importReusesExistingLectureForAlreadyImportedUrl() {
        when(mediaSources.findAll()).thenReturn(List.of(MediaSource.remote("existing",
                "https://www.youtube.com/watch?v=abc", "720p", false)));
        when(discovered.findByProviderAndExternalId("youtube", "abc"))
                .thenReturn(Optional.of(video("abc", "Title", "desc")));

        var result = service.importVideo(new VideoDiscoveryService.ImportRequest("youtube", "abc",
                "https://www.youtube.com/watch?v=abc", "720p", false, false));

        assertThat(result.lectureId()).isEqualTo("existing");
        assertThat(result.created()).isFalse();
        verify(remoteLectures, never()).createFromUrl(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
        verify(discovered).markImported("youtube", "abc", "existing");
    }

    @Test
    void importUsesExistingIngestionForNewVideo() {
        when(mediaSources.findAll()).thenReturn(List.of());
        when(remoteLectures.createFromUrl("https://www.youtube.com/watch?v=new", "720p", false,
                false, false)).thenReturn(lecture("new-lecture"));
        when(jobs.submit("new-lecture")).thenReturn(true);
        when(discovered.findByProviderAndExternalId("youtube", "new")).thenReturn(Optional.empty());

        var result = service.importVideo(new VideoDiscoveryService.ImportRequest("youtube", "new",
                "https://www.youtube.com/watch?v=new", "720p", false, false));

        assertThat(result.created()).isTrue();
        assertThat(result.submitted()).isTrue();
        assertThat(result.lectureId()).isEqualTo("new-lecture");
    }

    @Test
    void httpClientRejectsNonLoopbackPlainHttp() {
        DiscoveryHttpClient client = new DiscoveryHttpClient(properties, new ObjectMapper());
        assertThatThrownBy(() -> client.get("http://example.com/api", java.util.Map.of()))
                .isInstanceOf(DiscoveryHttpException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void parsesIsoDurationsFromYouTubeApi() {
        assertThat(YouTubeVideoSearchProvider.parseIsoDuration("PT1H2M3S")).isEqualTo(3_723_000L);
        assertThat(YouTubeVideoSearchProvider.parseIsoDuration("invalid")).isNull();
    }

    private static DiscoveredVideo video(String externalId, String title, String description) {
        return new DiscoveredVideo(0, "youtube", externalId,
                "https://www.youtube.com/watch?v=" + externalId, title, description, "Channel",
                600_000L, "en", Instant.parse("2024-01-01T00:00:00Z"), null, "query", null, null,
                null);
    }

    private static Lecture lecture(String id) {
        Instant now = Instant.now();
        return new Lecture(id, id + ".mp4", "source.mp4", 100, "video/mp4", JobStatus.COMPLETED,
                null, 600_000L, false, false, null, null, null, null, now, now);
    }
}
