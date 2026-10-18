package com.lectureprocessor.editor;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Persistence for versioned exports (audit trail of what was rendered when). */
@Repository
public class LectureExportRepository {

    private final JdbcTemplate jdbc;

    public LectureExportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LectureExport> MAPPER = (ResultSet rs, int rowNum) ->
            new LectureExport(
                    rs.getLong("id"), rs.getString("lecture_id"),
                    ExportVariant.valueOf(rs.getString("variant")),
                    rs.getString("artifact"), rs.getString("content_hash"),
                    rs.getObject("transcript_revision_id") == null
                            ? null : rs.getLong("transcript_revision_id"),
                    rs.getObject("translation_revision_id") == null
                            ? null : rs.getLong("translation_revision_id"),
                    rs.getObject("notes_revision_id") == null
                            ? null : rs.getLong("notes_revision_id"),
                    rs.getLong("size_bytes"), rs.getString("status"), rs.getString("error"),
                    Instant.parse(rs.getString("created_at")));

    public long insert(String lectureId, ExportVariant variant, String artifact, String contentHash,
            Long transcriptRevisionId, Long translationRevisionId, Long notesRevisionId,
            long sizeBytes, String status, String error) {
        jdbc.update("""
                INSERT INTO lecture_export (lecture_id, variant, artifact, content_hash,
                        transcript_revision_id, translation_revision_id, notes_revision_id,
                        size_bytes, status, error, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, lectureId, variant.name(), artifact, contentHash, transcriptRevisionId,
                translationRevisionId, notesRevisionId, sizeBytes, status, error,
                Instant.now().toString());
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id == null ? 0 : id;
    }

    public Optional<LectureExport> findById(String lectureId, long id) {
        return jdbc.query("""
                SELECT * FROM lecture_export WHERE lecture_id = ? AND id = ?
                """, MAPPER, lectureId, id).stream().findFirst();
    }

    public Optional<LectureExport> latest(String lectureId, ExportVariant variant) {
        return jdbc.query("""
                SELECT * FROM lecture_export WHERE lecture_id = ? AND variant = ?
                ORDER BY id DESC LIMIT 1
                """, MAPPER, lectureId, variant.name()).stream().findFirst();
    }

    public List<LectureExport> findByLecture(String lectureId, int limit) {
        return jdbc.query("""
                SELECT * FROM lecture_export WHERE lecture_id = ?
                ORDER BY id DESC LIMIT ?
                """, MAPPER, lectureId, Math.max(1, limit));
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM lecture_export WHERE lecture_id = ?", lectureId);
    }
}
