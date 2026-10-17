package com.lectureprocessor.discovery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageRepository;
import com.lectureprocessor.multilingual.MultilingualProperties;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.MediaSourceRepository;
import com.lectureprocessor.util.VectorMath;

/**
 * Local semantic recommendations over already processed lectures.
 *
 * <p>Similarity is measured, not generated: cosine similarity of topic-profile
 * embeddings (or a lexical Jaccard fallback when embeddings are unavailable).
 * The current lecture and duplicate imports of the same video are excluded.</p>
 */
@Service
public class LocalSimilarityService {

    private static final Logger log = LoggerFactory.getLogger(LocalSimilarityService.class);

    private final LectureRepository lectures;
    private final MediaSourceRepository mediaSources;
    private final LectureLanguageRepository languages;
    private final MultilingualProperties multilingual;
    private final TopicExtractionService topics;
    private final LectureTopicRepository topicRepository;
    private final LectureSimilarityRepository similarities;
    private final DiscoveryProperties properties;

    public LocalSimilarityService(LectureRepository lectures, MediaSourceRepository mediaSources,
            LectureLanguageRepository languages, MultilingualProperties multilingual,
            TopicExtractionService topics, LectureTopicRepository topicRepository,
            LectureSimilarityRepository similarities, DiscoveryProperties properties) {
        this.lectures = lectures;
        this.mediaSources = mediaSources;
        this.languages = languages;
        this.multilingual = multilingual;
        this.topics = topics;
        this.topicRepository = topicRepository;
        this.similarities = similarities;
        this.properties = properties;
    }

    /** Computes (and persists) recommendations for one lecture. */
    public List<SimilarLectureView> similar(String lectureId) {
        Lecture target = lectures.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("Лекция не найдена: " + lectureId));
        LectureTopic targetTopic = topics.ensure(lectureId);
        MediaSource targetSource = mediaSources.findByLecture(lectureId).orElse(null);
        Set<String> targetWords = TopicExtractionService.wordSet(targetTopic.profileText());

        List<SimilarLectureView> result = new ArrayList<>();
        for (Lecture candidate : lectures.findAll()) {
            if (candidate.id().equals(lectureId)) {
                continue;
            }
            if (candidate.status() != com.lectureprocessor.domain.JobStatus.COMPLETED
                    && candidate.status() != com.lectureprocessor.domain.JobStatus.PARTIAL) {
                continue;
            }
            MediaSource candidateSource = mediaSources.findByLecture(candidate.id()).orElse(null);
            if (duplicate(targetSource, target, candidateSource, candidate)) {
                continue;
            }
            LectureTopic candidateTopic;
            try {
                candidateTopic = topics.ensure(candidate.id());
            } catch (RuntimeException e) {
                log.debug("Skipping lecture {} for similarity: {}", candidate.id(), e.getMessage());
                continue;
            }
            Set<String> candidateWords = TopicExtractionService.wordSet(candidateTopic.profileText());
            double score;
            String basis;
            if (targetTopic.embedding() != null && candidateTopic.embedding() != null) {
                score = VectorMath.cosine(targetTopic.embedding(), candidateTopic.embedding());
                basis = "EMBEDDING_TOPIC_PROFILE";
            } else {
                score = TopicExtractionService.jaccard(targetWords, candidateWords);
                basis = "LEXICAL_KEYWORDS";
            }
            if (score + 1e-9 < properties.minSimilarity()) {
                continue;
            }
            List<String> common = targetTopic.keywords().stream()
                    .filter(candidateTopic.keywords()::contains)
                    .limit(6)
                    .toList();
            LectureLanguage language = languages.findOrDefault(candidate.id(), multilingual);
            result.add(new SimilarLectureView(candidate.id(), title(candidate, candidateSource),
                    platform(candidateSource), score, basis, common,
                    language.detectedLanguage(), duration(candidate, candidateSource),
                    candidate.status().name()));
        }
        result.sort(Comparator.comparingDouble(SimilarLectureView::score).reversed());
        List<SimilarLectureView> limited = result.stream().limit(properties.similarityTopK()).toList();
        similarities.replaceForLecture(lectureId, limited.stream()
                .map(v -> new LectureSimilarity(lectureId, v.lectureId(), v.score(), v.basis(),
                        v.commonTopics(), Instant.now()))
                .toList());
        return limited;
    }

    /** Persisted recommendations without recomputation (fast page render). */
    public List<SimilarLectureView> cached(String lectureId) {
        List<SimilarLectureView> result = new ArrayList<>();
        for (LectureSimilarity similarity : similarities.findByLecture(lectureId)) {
            Optional<Lecture> candidate = lectures.findById(similarity.similarLectureId());
            if (candidate.isEmpty()) {
                continue;
            }
            MediaSource source = mediaSources.findByLecture(candidate.get().id()).orElse(null);
            LectureLanguage language = languages.findOrDefault(candidate.get().id(), multilingual);
            result.add(new SimilarLectureView(candidate.get().id(),
                    title(candidate.get(), source), platform(source), similarity.score(),
                    similarity.basis(), similarity.commonTopics(), language.detectedLanguage(),
                    duration(candidate.get(), source), candidate.get().status().name()));
        }
        return result;
    }

    public void invalidate(String lectureId) {
        similarities.deleteByLecture(lectureId);
        topicRepository.delete(lectureId);
    }

    private static boolean duplicate(MediaSource targetSource, Lecture target, MediaSource source,
            Lecture candidate) {
        if (targetSource == null || source == null) {
            return false;
        }
        if (targetSource.originalUrl() != null && source.originalUrl() != null
                && canonical(targetSource.originalUrl()).equals(canonical(source.originalUrl()))) {
            return true;
        }
        if (targetSource.provider() != null && targetSource.externalId() != null
                && targetSource.provider().equals(source.provider())
                && targetSource.externalId().equals(source.externalId())) {
            return true;
        }
        return targetSource.checksum() != null && source.checksum() != null
                && targetSource.checksum().equals(source.checksum());
    }

    /** Stable URL form for duplicate detection (host case + trailing slash/params). */
    public static String canonical(String url) {
        if (url == null) {
            return "";
        }
        String value = url.strip().toLowerCase(Locale.ROOT);
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String title(Lecture lecture, MediaSource source) {
        if (source != null && source.title() != null && !source.title().isBlank()) {
            return source.title();
        }
        return lecture.originalFilename() == null || lecture.originalFilename().isBlank()
                ? lecture.id() : lecture.originalFilename();
    }

    private static String platform(MediaSource source) {
        if (source == null) {
            return "LOCAL";
        }
        return source.provider() != null ? source.provider() : source.kind().name();
    }

    private static Long duration(Lecture lecture, MediaSource source) {
        if (source != null && source.durationMs() != null) {
            return source.durationMs();
        }
        return lecture.durationMs();
    }
}
