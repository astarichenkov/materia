package com.lectureprocessor.lecture;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.editor.DocumentType;
import com.lectureprocessor.editor.DocumentRevisionRepository;
import com.lectureprocessor.editor.LectureDocumentRevision;

/**
 * Practical library view: search by title/topic, filter by language, processing
 * status and translation availability. It reuses the existing lecture tables and
 * RAG topics — no second search system is introduced.
 */
@Service
public class LectureLibraryService {

    private final JdbcTemplate jdbc;
    private final DocumentRevisionRepository revisions;

    public LectureLibraryService(JdbcTemplate jdbc, DocumentRevisionRepository revisions) {
        this.jdbc = jdbc;
        this.revisions = revisions;
    }

    /**
     * @param query           free text over the filename and the RAG-generated topic
     * @param language        detected/source language ISO code (blank = any)
     * @param status          {@link JobStatus} name (blank = any)
     * @param hasTranslation  {@code true}/{@code false} to filter; {@code null} = any
     * @param sort            {@code date-desc} (default) or {@code date-asc}, {@code title}
     */
    public List<LibraryItem> search(String query, String language, String status,
            Boolean hasTranslation, String sort) {
        String sql = """
                SELECT l.*, ll.detected_language, ll.translation_mode,
                       t.id AS translation_id, t.status AS translation_status,
                       t.segments_translated, t.segments_total,
                       tp.title AS topic_title, tp.keywords AS topic_keywords
                FROM lecture l
                LEFT JOIN lecture_language ll ON ll.lecture_id = l.id
                LEFT JOIN lecture_topic tp ON tp.lecture_id = l.id
                LEFT JOIN lecture_translation t ON t.id = (
                    SELECT MAX(id) FROM lecture_translation WHERE lecture_id = l.id)
                """;
        List<LibraryItem> items = jdbc.query(sql, (rs, rowNum) -> new LibraryItem(
                rs.getString("id"),
                rs.getString("original_filename"),
                JobStatus.valueOf(rs.getString("status")),
                rs.getString("created_at") == null ? null : Instant.parse(rs.getString("created_at")),
                rs.getString("detected_language"),
                rs.getString("translation_mode"),
                rs.getString("translation_status"),
                rs.getObject("segments_translated") == null ? 0 : rs.getInt("segments_translated"),
                rs.getObject("segments_total") == null ? 0 : rs.getInt("segments_total"),
                rs.getString("topic_title"),
                rs.getString("topic_keywords")));

        String needle = normalize(query);
        String lang = normalize(language);
        String jobStatus = normalize(status);
        List<LibraryItem> filtered = items.stream()
                .filter(item -> needle.isEmpty()
                        || normalize(item.filename()).contains(needle)
                        || normalize(item.topicTitle()).contains(needle)
                        || normalize(item.topicKeywords()).contains(needle))
                .filter(item -> lang.isEmpty() || lang.equals(normalize(item.detectedLanguage())))
                .filter(item -> jobStatus.isEmpty()
                        || jobStatus.equals(item.status().name().toLowerCase(Locale.ROOT)))
                .filter(item -> hasTranslation == null || item.hasTranslation() == hasTranslation)
                .toList();

        Comparator<LibraryItem> comparator = switch (normalize(sort)) {
            case "date-asc" -> Comparator.comparing(LibraryItem::createdAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "title" -> Comparator.comparing(item -> normalize(item.filename()));
            default -> Comparator.comparing(LibraryItem::createdAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        };
        return filtered.stream().sorted(comparator).toList();
    }

    /** Latest editor revision number, or {@code null} when the editor has no revision yet. */
    public Integer editorRevision(String lectureId) {
        return revisions.latest(lectureId, DocumentType.NOTES)
                .map(LectureDocumentRevision::revisionNumber).orElse(null);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * One row of the library table. Kept free of persistence concerns so the UI
     * template stays simple.
     */
    public record LibraryItem(
            String lectureId,
            String filename,
            JobStatus status,
            Instant createdAt,
            String detectedLanguage,
            String translationMode,
            String translationStatus,
            int segmentsTranslated,
            int segmentsTotal,
            String topicTitle,
            String topicKeywords) {

        public boolean hasTranslation() {
            return translationStatus != null && segmentsTranslated > 0
                    && ("COMPLETED".equals(translationStatus) || "PARTIAL".equals(translationStatus));
        }

        public String title() {
            return topicTitle == null || topicTitle.isBlank() ? filename : topicTitle;
        }
    }
}
