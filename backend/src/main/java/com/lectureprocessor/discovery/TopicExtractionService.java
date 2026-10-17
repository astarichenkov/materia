package com.lectureprocessor.discovery;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.llm.EmbeddingProvider;
import com.lectureprocessor.pipeline.PipelineDataLoader;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.MediaSourceRepository;
import com.lectureprocessor.text.StructuredNotes;
import com.lectureprocessor.util.FileChecksum;

/**
 * Builds a semantic topic profile from several signals: title, structured notes
 * summary/headings, keywords and the transcript. The resulting profile is
 * embedded with the existing local {@link EmbeddingProvider}; no cloud LLM is
 * called for topic extraction.
 */
@Service
public class TopicExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TopicExtractionService.class);
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}+#._-]{2,}");
    private static final int MAX_TRANSCRIPT_CHARS = 4000;
    private static final Set<String> STOPWORDS = Set.of(
            "это", "как", "для", "что", "все", "или", "так", "его", "она", "они", "мы", "вы",
            "быть", "есть", "были", "было", "который", "которая", "которые", "когда", "только",
            "очень", "можно", "нужно", "также", "более", "через", "после", "перед", "между",
            "будет", "будут", "был", "была", "этот", "эта", "эти", "того", "чем", "при", "без",
            "the", "and", "for", "with", "that", "this", "from", "have", "has", "are", "was",
            "were", "will", "would", "can", "could", "should", "you", "your", "our", "their",
            "not", "but", "about", "into", "than", "then", "there", "these", "those", "which",
            "what", "when", "where", "how", "why", "all", "any", "some", "one", "two", "its");

    private final LectureRepository lectures;
    private final MediaSourceRepository mediaSources;
    private final PipelineDataLoader loader;
    private final EmbeddingProvider embeddings;
    private final LectureTopicRepository topics;

    public TopicExtractionService(LectureRepository lectures, MediaSourceRepository mediaSources,
            PipelineDataLoader loader, EmbeddingProvider embeddings, LectureTopicRepository topics) {
        this.lectures = lectures;
        this.mediaSources = mediaSources;
        this.loader = loader;
        this.embeddings = embeddings;
        this.topics = topics;
    }

    /** Returns a cached topic profile when its source hash still matches. */
    public LectureTopic ensure(String lectureId) {
        Lecture lecture = lectures.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("Лекция не найдена: " + lectureId));
        SourceMaterial material = material(lecture);
        String hash = FileChecksum.sha256(material.title() + "|" + material.summary() + "|"
                + material.notes() + "|" + material.transcript());
        var existing = topics.find(lectureId);
        if (existing.isPresent() && hash.equals(existing.get().sourceHash())
                && existing.get().embedding() != null) {
            return existing.get();
        }
        List<String> keywords = keywords(material);
        List<String> keyTopics = keyTopics(material, keywords);
        String profileText = buildProfile(material, keyTopics, keywords);
        float[] embedding = embed(profileText);
        LectureTopic topic = new LectureTopic(lectureId, material.title(), material.summary(),
                keyTopics, keywords, profileText, embedding, hash, java.time.Instant.now());
        topics.save(topic);
        return topic;
    }

    private SourceMaterial material(Lecture lecture) {
        var source = mediaSources.findByLecture(lecture.id());
        String title = source.map(s -> s.title()).filter(t -> t != null && !t.isBlank())
                .orElse(lecture.originalFilename());
        StructuredNotes notes = loader.notes(lecture.id());
        String summary = notes != null && notes.summary() != null ? notes.summary() : "";
        String notesText = notes != null && notes.markdown() != null ? notes.markdown() : "";
        Transcript transcript = loader.transcript(lecture.id());
        String transcriptText = transcript != null ? transcript.plainText() : "";
        if (transcriptText.length() > MAX_TRANSCRIPT_CHARS) {
            transcriptText = transcriptText.substring(0, MAX_TRANSCRIPT_CHARS);
        }
        if (notesText.isBlank() && lecture.notesMarkdown() != null) {
            notesText = lecture.notesMarkdown();
        }
        return new SourceMaterial(title == null ? "" : title, summary, notesText, transcriptText);
    }

    private static List<String> keywords(SourceMaterial material) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher matcher = WORD.matcher((material.title() + " " + material.summary() + " "
                + material.notes() + " " + material.transcript()).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (STOPWORDS.contains(word) || word.length() < 3
                    || word.chars().allMatch(Character::isDigit)) {
                continue;
            }
            counts.merge(word, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(15)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static List<String> keyTopics(SourceMaterial material, List<String> keywords) {
        Set<String> result = new LinkedHashSet<>();
        for (String line : material.notes().split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith("## ") || stripped.startsWith("### ")) {
                String heading = stripped.replaceFirst("^#+\\s*", "").strip();
                if (heading.length() >= 3 && !heading.equalsIgnoreCase("Резюме")) {
                    result.add(heading);
                }
            }
        }
        keywords.stream().limit(8).forEach(result::add);
        return result.stream().limit(12).toList();
    }

    private static String buildProfile(SourceMaterial material, List<String> keyTopics,
            List<String> keywords) {
        return (material.title() + "\n" + material.summary() + "\n"
                + String.join(", ", keyTopics) + "\n" + String.join(", ", keywords) + "\n"
                + material.notes() + "\n" + material.transcript()).strip();
    }

    private float[] embed(String text) {
        try {
            if (!embeddings.isAvailable()) {
                return null;
            }
            return embeddings.embed(List.of(text)).getFirst();
        } catch (RuntimeException e) {
            log.warn("Topic embedding unavailable: {}", e.getMessage());
            return null;
        }
    }

    /** Static word set for lexical fallback comparisons. */
    public static Set<String> wordSet(String text) {
        Set<String> words = new LinkedHashSet<>();
        if (text == null) {
            return words;
        }
        Matcher matcher = WORD.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            if (!STOPWORDS.contains(matcher.group()) && matcher.group().length() >= 3) {
                words.add(matcher.group());
            }
        }
        return words;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private record SourceMaterial(String title, String summary, String notes, String transcript) {
    }
}
