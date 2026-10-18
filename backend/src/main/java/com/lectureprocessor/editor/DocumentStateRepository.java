package com.lectureprocessor.editor;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for explicit document staleness flags. */
@Repository
public class DocumentStateRepository {

    private final JdbcTemplate jdbc;

    public DocumentStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<DocumentType, LectureDocumentState> findByLecture(String lectureId) {
        List<LectureDocumentState> rows = jdbc.query("""
                SELECT * FROM lecture_document_state WHERE lecture_id = ?
                """, (ResultSet rs, int rowNum) -> new LectureDocumentState(
                        rs.getString("lecture_id"),
                        DocumentType.valueOf(rs.getString("document_type")),
                        DocumentStatus.valueOf(rs.getString("status")),
                        rs.getString("reason"),
                        Instant.parse(rs.getString("updated_at"))),
                lectureId);
        Map<DocumentType, LectureDocumentState> result = new EnumMap<>(DocumentType.class);
        rows.forEach(row -> result.put(row.documentType(), row));
        return result;
    }

    public void set(String lectureId, DocumentType type, DocumentStatus status, String reason) {
        jdbc.update("""
                INSERT INTO lecture_document_state (lecture_id, document_type, status, reason, updated_at)
                VALUES (?,?,?,?,?)
                ON CONFLICT(lecture_id, document_type) DO UPDATE SET
                        status = excluded.status, reason = excluded.reason,
                        updated_at = excluded.updated_at
                """, lectureId, type.name(), status.name(), reason, Instant.now().toString());
    }

    public void markCurrent(String lectureId, DocumentType type) {
        set(lectureId, type, DocumentStatus.CURRENT, null);
    }

    public void markStale(String lectureId, DocumentType type, String reason) {
        set(lectureId, type, DocumentStatus.STALE, reason);
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM lecture_document_state WHERE lecture_id = ?", lectureId);
    }
}
