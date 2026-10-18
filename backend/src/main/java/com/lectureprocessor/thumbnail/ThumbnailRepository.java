package com.lectureprocessor.thumbnail;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** CRUD for the thumbnail cache table. One row per lecture. */
@Repository
public class ThumbnailRepository {

    private static final RowMapper<LectureThumbnail> MAPPER = (ResultSet rs, int rowNum) ->
            new LectureThumbnail(
                    rs.getString("lecture_id"),
                    ThumbnailStatus.valueOf(rs.getString("status")),
                    rs.getString("source"),
                    rs.getString("relative_path"),
                    rs.getLong("size_bytes"),
                    rs.getString("error"),
                    Instant.parse(rs.getString("updated_at")));

    private final JdbcTemplate jdbc;

    public ThumbnailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LectureThumbnail> find(String lectureId) {
        return jdbc.query("SELECT * FROM lecture_thumbnail WHERE lecture_id = ?", MAPPER, lectureId)
                .stream().findFirst();
    }

    public void save(LectureThumbnail thumbnail) {
        jdbc.update("""
                INSERT INTO lecture_thumbnail (lecture_id, status, source, relative_path,
                        size_bytes, error, updated_at)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(lecture_id) DO UPDATE SET
                        status = excluded.status,
                        source = excluded.source,
                        relative_path = excluded.relative_path,
                        size_bytes = excluded.size_bytes,
                        error = excluded.error,
                        updated_at = excluded.updated_at
                """, thumbnail.lectureId(), thumbnail.status().name(), thumbnail.source(),
                thumbnail.relativePath(), thumbnail.sizeBytes(), thumbnail.error(),
                thumbnail.updatedAt().toString());
    }

    /**
     * Lectures that have no usable thumbnail yet, newest first. Bounded so a
     * backfill can never become an unbounded job over the whole library.
     */
    public List<String> missingThumbnails(int limit) {
        return jdbc.queryForList("""
                SELECT l.id FROM lecture l
                LEFT JOIN lecture_thumbnail t ON t.lecture_id = l.id
                WHERE t.lecture_id IS NULL
                   OR (t.status = 'MISSING')
                ORDER BY l.created_at DESC
                LIMIT ?
                """, String.class, Math.max(1, limit));
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM lecture_thumbnail WHERE lecture_id = ?", lectureId);
    }
}
