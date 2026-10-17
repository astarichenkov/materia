package com.lectureprocessor.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageRepository;
import com.lectureprocessor.multilingual.MultilingualProperties;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.MediaSourceRepository;

class LocalSimilarityServiceTest {

    private final LectureRepository lectures = mock(LectureRepository.class);
    private final MediaSourceRepository mediaSources = mock(MediaSourceRepository.class);
    private final LectureLanguageRepository languages = mock(LectureLanguageRepository.class);
    private final TopicExtractionService topics = mock(TopicExtractionService.class);
    private final LectureTopicRepository topicRepository = mock(LectureTopicRepository.class);
    private final LectureSimilarityRepository similarities = mock(LectureSimilarityRepository.class);

    private final LocalSimilarityService service = new LocalSimilarityService(lectures,
            mediaSources, languages, MultilingualProperties.defaults(), topics, topicRepository,
            similarities, DiscoveryProperties.defaults());

    @Test
    void ranksSimilarLecturesAndExcludesSelfAndDuplicateImports() {
        Lecture target = lecture("target");
        Lecture similar = lecture("similar");
        Lecture duplicate = lecture("duplicate");
        Lecture unrelated = lecture("unrelated");
        when(lectures.findAll()).thenReturn(List.of(target, similar, duplicate, unrelated));
        when(lectures.findById("target")).thenReturn(Optional.of(target));
        when(mediaSources.findByLecture("target")).thenReturn(Optional.of(remote("target", "https://youtu.be/x",
                "ext-1")));
        when(mediaSources.findByLecture("similar")).thenReturn(Optional.of(remote("similar",
                "https://youtu.be/similar", "ext-2")));
        when(mediaSources.findByLecture("duplicate")).thenReturn(Optional.of(remote("duplicate",
                "https://youtu.be/x", "ext-1")));
        when(mediaSources.findByLecture("unrelated")).thenReturn(Optional.of(remote("unrelated",
                "https://youtu.be/unrelated", "ext-3")));
        when(languages.findOrDefault(any(), any())).thenReturn(new LectureLanguage("x",
                com.lectureprocessor.multilingual.LanguageMode.AUTO, "en", null, null,
                com.lectureprocessor.multilingual.TranslationMode.ORIGINAL_ONLY, "ru", false,
                Instant.now()));

        when(topics.ensure("target")).thenReturn(topic("target", new float[] {1, 0, 0},
                List.of("embeddings", "retrieval")));
        when(topics.ensure("similar")).thenReturn(topic("similar", new float[] {0.9f, 0.1f, 0},
                List.of("embeddings", "retrieval")));
        when(topics.ensure("duplicate")).thenReturn(topic("duplicate", new float[] {1, 0, 0},
                List.of("embeddings")));
        when(topics.ensure("unrelated")).thenReturn(topic("unrelated", new float[] {0, 1, 0},
                List.of("cooking")));

        List<SimilarLectureView> result = service.similar("target");

        assertThat(result).extracting(SimilarLectureView::lectureId).containsExactly("similar");
        assertThat(result.getFirst().score()).isGreaterThan(0.8);
        assertThat(result.getFirst().basis()).isEqualTo("EMBEDDING_TOPIC_PROFILE");
        assertThat(result.getFirst().commonTopics()).contains("embeddings", "retrieval");
        assertThat(result.getFirst().scorePercent()).isBetween(80, 100);
        verify(similarities).replaceForLecture(any(), any());
    }

    @Test
    void fallsBackToMeasuredLexicalSimilarityWithoutEmbeddings() {
        Lecture target = lecture("target");
        Lecture other = lecture("other");
        when(lectures.findAll()).thenReturn(List.of(target, other));
        when(lectures.findById("target")).thenReturn(Optional.of(target));
        when(mediaSources.findByLecture("target")).thenReturn(Optional.empty());
        when(mediaSources.findByLecture("other")).thenReturn(Optional.empty());
        when(languages.findOrDefault(any(), any())).thenReturn(new LectureLanguage("x",
                com.lectureprocessor.multilingual.LanguageMode.AUTO, null, null, null,
                com.lectureprocessor.multilingual.TranslationMode.ORIGINAL_ONLY, "ru", false,
                Instant.now()));
        when(topics.ensure("target")).thenReturn(new LectureTopic("target", "Machine learning",
                "", List.of(), List.of(), "machine learning embeddings retrieval", null, "h",
                Instant.now()));
        when(topics.ensure("other")).thenReturn(new LectureTopic("other", "ML notes", "",
                List.of(), List.of(), "machine learning embeddings retrieval tutorial", null, "h2",
                Instant.now()));

        List<SimilarLectureView> result = service.similar("target");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().basis()).isEqualTo("LEXICAL_KEYWORDS");
        assertThat(result.getFirst().score()).isBetween(0.0, 1.0);
    }

    private static Lecture lecture(String id) {
        Instant now = Instant.now();
        return new Lecture(id, id + ".mp4", "source.mp4", 100, "video/mp4", JobStatus.COMPLETED,
                null, 1000L, false, false, null, null, null, null, now, now);
    }

    private static MediaSource remote(String lectureId, String url, String externalId) {
        return MediaSource.remote(lectureId, url, "720p", false)
                .withMetadata("YouTube", externalId, lectureId, null, 1000L, null, "sum-" + lectureId);
    }

    private static LectureTopic topic(String id, float[] embedding, List<String> keywords) {
        return new LectureTopic(id, id, "", keywords, keywords, String.join(" ", keywords),
                embedding, "hash-" + id, Instant.now());
    }
}
