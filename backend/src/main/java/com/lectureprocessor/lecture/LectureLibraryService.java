package com.lectureprocessor.lecture;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.editor.DocumentRevisionRepository;
import com.lectureprocessor.editor.DocumentType;
import com.lectureprocessor.editor.LectureDocumentRevision;

/**
 * Practical library view: search by title/topic, filter by language, processing
 * status and translation availability. It reuses the existing lecture tables and
 * RAG topics — no second search system is introduced.
 *
 * <p>{@link #searchPage} performs filtering, sorting and pagination in SQL so a
 * dashboard/library screen never loads the whole lecture table into memory.</p>
 */
@Service
public class LectureLibraryService {

    private static final String SELECT_COLUMNS = """
            SELECT l.*, ll.detected_language, ll.translation_mode,
                   t.id AS translation_id, t.status AS translation_status,
                   t.segments_translated, t.segments_total,
                   tp.title AS topic_title, tp.keywords AS topic_keywords,
                   th.status AS thumbnail_status, th.relative_path AS thumbnail_path
            """;

    private static final String FROM_JOINS = """
            FROM lecture l
            LEFT JOIN lecture_language ll ON ll.lecture_id = l.id
            LEFT JOIN lecture_topic tp ON tp.lecture_id = l.id
            LEFT JOIN lecture_thumbnail th ON th.lecture_id = l.id
            LEFT JOIN lecture_translation t ON t.id = (
                SELECT MAX(id) FROM lecture_translation WHERE lecture_id = l.id)
            """;

    private static final String TRANSLATION_PRESENT =
            "(t.status IN ('COMPLETED', 'PARTIAL') AND COALESCE(t.segments_translated, 0) > 0)";

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
        Filter filter = filter(query, language, status, hasTranslation);
        List<LibraryItem> items = jdbc.query(SELECT_COLUMNS + FROM_JOINS + filter.where(),
                this::mapRow, filter.arguments().toArray());

        Comparator<LibraryItem> comparator = switch (normalize(sort)) {
            case "date-asc" -> Comparator.comparing(LibraryItem::createdAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "title" -> Comparator.comparing(item -> normalize(item.title()));
            default -> Comparator.comparing(LibraryItem::createdAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        };
        return items.stream().sorted(comparator).toList();
    }

    /**
     * One bounded, server-side page. Counts and rows use the same joins/filters,
     * so the total always matches the visible filter set.
     */
    public LibraryPage searchPage(String query, String language, String status,
            Boolean hasTranslation, String sort, int page, int pageSize) {
        Filter filter = filter(query, language, status, hasTranslation);
        int size = Math.max(1, Math.min(pageSize, 100));
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + FROM_JOINS + filter.where(),
                Long.class, filter.arguments().toArray());
        long totalItems = total == null ? 0 : total;
        int totalPages = totalItems == 0 ? 0 : (int) Math.ceil(totalItems / (double) size);
        int safePage = totalPages == 0 ? 1 : Math.min(Math.max(1, page), totalPages);
        int offset = (safePage - 1) * size;

        List<Object> args = new ArrayList<>(filter.arguments());
        args.add(size);
        args.add(offset);
        List<LibraryItem> items = jdbc.query(
                SELECT_COLUMNS + FROM_JOINS + filter.where() + orderBy(sort) + " LIMIT ? OFFSET ?",
                this::mapRow, args.toArray());
        return new LibraryPage(items, totalItems, safePage, size, totalPages);
    }

    /** Newest materials with one of the given statuses (bounded, single query). */
    public List<LibraryItem> recentByStatuses(Collection<JobStatus> statuses, int limit) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        List<String> names = statuses.stream().map(Enum::name).toList();
        String placeholders = names.stream().map(name -> "?").collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>(names);
        args.add(Math.max(1, limit));
        return jdbc.query(SELECT_COLUMNS + FROM_JOINS + " WHERE l.status IN (" + placeholders + ")"
                + " ORDER BY l.created_at DESC LIMIT ?", this::mapRow, args.toArray());
    }

    public long countAll() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM lecture", Long.class);
        return count == null ? 0 : count;
    }

    /** Distinct detected languages without loading lecture rows. */
    public List<String> languages() {
        return jdbc.queryForList("""
                SELECT DISTINCT lower(detected_language) AS code
                FROM lecture_language
                WHERE detected_language IS NOT NULL AND trim(detected_language) <> ''
                ORDER BY code
                """, String.class);
    }

    /** Latest editor revision number, or {@code null} when the editor has no revision yet. */
    public Integer editorRevision(String lectureId) {
        return revisions.latest(lectureId, DocumentType.NOTES)
                .map(LectureDocumentRevision::revisionNumber).orElse(null);
    }

    private Filter filter(String query, String language, String status, Boolean hasTranslation) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        String needle = normalize(query);
        if (!needle.isEmpty()) {
            String pattern = "%" + escapeLike(needle) + "%";
            clauses.add("""
                    (lower(l.original_filename) LIKE ? ESCAPE '\\'
                     OR lower(COALESCE(tp.title, '')) LIKE ? ESCAPE '\\'
                     OR lower(COALESCE(tp.keywords, '')) LIKE ? ESCAPE '\\')
                    """.strip());
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        String lang = normalize(language);
        if (!lang.isEmpty()) {
            clauses.add("lower(COALESCE(ll.detected_language, '')) = ?");
            args.add(lang);
        }
        String jobStatus = normalize(status);
        if (!jobStatus.isEmpty()) {
            String name = jobStatus.toUpperCase(Locale.ROOT);
            try {
                JobStatus.valueOf(name);
                clauses.add("l.status = ?");
                args.add(name);
            } catch (IllegalArgumentException e) {
                clauses.add("1 = 0");
            }
        }
        if (hasTranslation != null) {
            clauses.add(hasTranslation
                    ? TRANSLATION_PRESENT
                    : "(t.id IS NULL OR NOT " + TRANSLATION_PRESENT + ")");
        }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        return new Filter(where, args);
    }

    private static String orderBy(String sort) {
        return switch (normalize(sort)) {
            case "date-asc" -> " ORDER BY l.created_at ASC";
            case "title" -> """
                     ORDER BY lower(COALESCE(NULLIF(tp.title, ''), l.original_filename)) ASC,
                              l.created_at DESC
                    """.strip();
            default -> " ORDER BY l.created_at DESC";
        };
    }

    private LibraryItem mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new LibraryItem(
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
                rs.getString("topic_keywords"),
                rs.getString("thumbnail_status"),
                rs.getString("thumbnail_path"));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private record Filter(String where, List<Object> arguments) {
    }

    /** Bounded page returned by {@link #searchPage}. */
    public record LibraryPage(List<LibraryItem> items, long total, int page, int pageSize,
            int totalPages) {

        public boolean hasPrevious() {
            return page > 1;
        }

        public boolean hasNext() {
            return page < totalPages;
        }
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
            String topicKeywords,
            String thumbnailStatus,
            String thumbnailPath) {

        public boolean hasThumbnail() {
            return "READY".equals(thumbnailStatus) && thumbnailPath != null;
        }

        public boolean hasTranslation() {
            return translationStatus != null && segmentsTranslated > 0
                    && ("COMPLETED".equals(translationStatus) || "PARTIAL".equals(translationStatus));
        }

        public String title() {
            return topicTitle == null || topicTitle.isBlank() ? filename : topicTitle;
        }
    }
}
