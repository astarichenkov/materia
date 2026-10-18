package com.lectureprocessor.editor;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Persistence for immutable document revisions. */
@Repository
public class DocumentRevisionRepository {

    private final JdbcTemplate jdbc;

    public DocumentRevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LectureDocumentRevision> MAPPER =
            (ResultSet rs, int rowNum) -> new LectureDocumentRevision(
                    rs.getLong("id"),
                    rs.getString("lecture_id"),
                    DocumentType.valueOf(rs.getString("document_type")),
                    rs.getInt("revision_number"),
                    rs.getString("content"),
                    rs.getString("content_hash"),
                    rs.getObject("parent_revision_id") == null
                            ? null : rs.getLong("parent_revision_id"),
                    EditSource.valueOf(rs.getString("edit_source")),
                    rs.getString("description"),
                    Instant.parse(rs.getString("created_at")));

    public Optional<LectureDocumentRevision> latest(String lectureId, DocumentType type) {
        return jdbc.query("""
                SELECT * FROM lecture_document_revision
                WHERE lecture_id = ? AND document_type = ?
                ORDER BY revision_number DESC LIMIT 1
                """, MAPPER, lectureId, type.name()).stream().findFirst();
    }

    public Optional<LectureDocumentRevision> findById(String lectureId, DocumentType type, long id) {
        return jdbc.query("""
                SELECT * FROM lecture_document_revision
                WHERE lecture_id = ? AND document_type = ? AND id = ?
                """, MAPPER, lectureId, type.name(), id).stream().findFirst();
    }

    public List<LectureDocumentRevision> history(String lectureId, DocumentType type, int limit) {
        return jdbc.query("""
                SELECT * FROM lecture_document_revision
                WHERE lecture_id = ? AND document_type = ?
                ORDER BY revision_number DESC LIMIT ?
                """, MAPPER, lectureId, type.name(), Math.max(1, limit));
    }

    public int nextRevisionNumber(String lectureId, DocumentType type) {
        Integer current = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision_number), 0) FROM lecture_document_revision
                WHERE lecture_id = ? AND document_type = ?
                """, Integer.class, lectureId, type.name());
        return (current == null ? 0 : current) + 1;
    }

    public LectureDocumentRevision insert(String lectureId, DocumentType type, int revisionNumber,
            String content, String contentHash, Long parentId, EditSource source, String description) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO lecture_document_revision (lecture_id, document_type, revision_number,
                        content, content_hash, parent_revision_id, edit_source, description, created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, lectureId, type.name(), revisionNumber, content, contentHash, parentId,
                source.name(), description, now.toString());
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return findById(lectureId, type, id).orElseThrow();
    }

    /**
     * Deletes revisions above the retention limit. The very first revision of a
     * document (the original model output) is never deleted, so the immutable
     * Whisper/LLM source stays recoverable.
     */
    public int prune(String lectureId, DocumentType type, int keep) {
        int safeKeep = Math.max(2, keep);
        Long lowest = jdbc.queryForObject("""
                SELECT MIN(revision_number) FROM lecture_document_revision
                WHERE lecture_id = ? AND document_type = ?
                """, Long.class, lectureId, type.name());
        if (lowest == null) {
            return 0;
        }
        return jdbc.update("""
                DELETE FROM lecture_document_revision
                WHERE lecture_id = ? AND document_type = ? AND revision_number > ?
                  AND revision_number NOT IN (
                      SELECT revision_number FROM lecture_document_revision
                      WHERE lecture_id = ? AND document_type = ?
                      ORDER BY revision_number DESC LIMIT ?)
                """, lectureId, type.name(), lowest, lectureId, type.name(), safeKeep);
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM lecture_document_revision WHERE lecture_id = ?", lectureId);
    }
}
