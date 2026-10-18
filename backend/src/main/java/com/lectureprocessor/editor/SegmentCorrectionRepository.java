package com.lectureprocessor.editor;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for per-segment correction and review state. */
@Repository
public class SegmentCorrectionRepository {

    private final JdbcTemplate jdbc;

    public SegmentCorrectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Integer, LectureSegmentCorrection> findByLecture(String lectureId, DocumentType type) {
        List<LectureSegmentCorrection> rows = jdbc.query("""
                SELECT * FROM lecture_segment_correction
                WHERE lecture_id = ? AND document_type = ? ORDER BY sequence
                """, (ResultSet rs, int rowNum) -> new LectureSegmentCorrection(
                        rs.getLong("id"), rs.getString("lecture_id"),
                        DocumentType.valueOf(rs.getString("document_type")),
                        rs.getInt("sequence"),
                        rs.getObject("translation_id") == null ? null : rs.getLong("translation_id"),
                        rs.getString("original_text"), rs.getString("corrected_text"),
                        SegmentReviewStatus.valueOf(rs.getString("review_status")),
                        rs.getString("note"), rs.getString("provider"), rs.getString("model"),
                        Instant.parse(rs.getString("updated_at"))),
                lectureId, type.name());
        return rows.stream().collect(Collectors.toMap(LectureSegmentCorrection::sequence,
                Function.identity(), (first, second) -> second));
    }

    public Optional<LectureSegmentCorrection> find(String lectureId, DocumentType type, int sequence) {
        return jdbc.query("""
                SELECT * FROM lecture_segment_correction
                WHERE lecture_id = ? AND document_type = ? AND sequence = ?
                """, (ResultSet rs, int rowNum) -> new LectureSegmentCorrection(
                        rs.getLong("id"), rs.getString("lecture_id"),
                        DocumentType.valueOf(rs.getString("document_type")),
                        rs.getInt("sequence"),
                        rs.getObject("translation_id") == null ? null : rs.getLong("translation_id"),
                        rs.getString("original_text"), rs.getString("corrected_text"),
                        SegmentReviewStatus.valueOf(rs.getString("review_status")),
                        rs.getString("note"), rs.getString("provider"), rs.getString("model"),
                        Instant.parse(rs.getString("updated_at"))),
                lectureId, type.name(), sequence).stream().findFirst();
    }

    /**
     * Inserts or updates a correction. {@code originalText} is only written on
     * the first correction so the true model output is preserved.
     */
    public void upsert(String lectureId, DocumentType type, int sequence, Long translationId,
            String originalText, String correctedText, SegmentReviewStatus status, String note,
            String provider, String model) {
        jdbc.update("""
                INSERT INTO lecture_segment_correction (lecture_id, document_type, sequence,
                        translation_id, original_text, corrected_text, review_status, note,
                        provider, model, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(lecture_id, document_type, sequence) DO UPDATE SET
                        translation_id = excluded.translation_id,
                        corrected_text = excluded.corrected_text,
                        review_status = excluded.review_status,
                        note = COALESCE(excluded.note, lecture_segment_correction.note),
                        provider = COALESCE(excluded.provider, lecture_segment_correction.provider),
                        model = COALESCE(excluded.model, lecture_segment_correction.model),
                        updated_at = excluded.updated_at
                """, lectureId, type.name(), sequence, translationId, originalText, correctedText,
                status.name(), note, provider, model, Instant.now().toString());
    }

    /** Updates only the review status of an existing correction. */
    public void updateReviewStatus(String lectureId, DocumentType type, int sequence,
            SegmentReviewStatus status) {
        jdbc.update("""
                UPDATE lecture_segment_correction SET review_status = ?, updated_at = ?
                WHERE lecture_id = ? AND document_type = ? AND sequence = ?
                """, status.name(), Instant.now().toString(), lectureId, type.name(), sequence);
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM lecture_segment_correction WHERE lecture_id = ?", lectureId);
    }
}
