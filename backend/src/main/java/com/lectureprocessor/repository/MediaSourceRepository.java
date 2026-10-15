package com.lectureprocessor.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.media.MediaSourceKind;

@Repository
public class MediaSourceRepository {

    private final JdbcTemplate jdbc;

    public MediaSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<MediaSource> MAPPER = (ResultSet rs, int rowNum) -> new MediaSource(
            rs.getString("lecture_id"),
            MediaSourceKind.valueOf(rs.getString("kind")),
            rs.getString("original_filename"),
            rs.getString("original_url"),
            rs.getString("quality"),
            rs.getInt("audio_only") == 1,
            rs.getString("provider"),
            rs.getString("external_id"),
            rs.getString("title"),
            rs.getString("uploader"),
            rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
            rs.getString("metadata_json"),
            rs.getString("checksum"),
            Instant.parse(rs.getString("created_at")));

    public void save(MediaSource source) {
        jdbc.update("""
                INSERT INTO media_source (lecture_id, kind, original_url, original_filename, provider,
                        external_id, title, uploader, duration_ms, quality, audio_only, metadata_json,
                        checksum, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(lecture_id) DO UPDATE SET
                        kind=excluded.kind,
                        original_url=excluded.original_url,
                        original_filename=excluded.original_filename,
                        provider=excluded.provider,
                        external_id=excluded.external_id,
                        title=excluded.title,
                        uploader=excluded.uploader,
                        duration_ms=excluded.duration_ms,
                        quality=excluded.quality,
                        audio_only=excluded.audio_only,
                        metadata_json=excluded.metadata_json,
                        checksum=excluded.checksum
                """,
                source.lectureId(), source.kind().name(), source.originalUrl(),
                source.originalFilename(), source.provider(), source.externalId(), source.title(),
                source.uploader(), source.durationMs(), source.quality(), source.audioOnly() ? 1 : 0,
                source.metadataJson(), source.checksum(), source.createdAt().toString());
    }

    public Optional<MediaSource> findByLecture(String lectureId) {
        List<MediaSource> list = jdbc.query(
                "SELECT * FROM media_source WHERE lecture_id = ?", MAPPER, lectureId);
        return list.stream().findFirst();
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM media_source WHERE lecture_id = ?", lectureId);
    }
}
