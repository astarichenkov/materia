package com.lectureprocessor.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.download.DownloadState;

@Repository
public class DownloadStateRepository {

    private final JdbcTemplate jdbc;

    public DownloadStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DownloadState> MAPPER = (ResultSet rs, int rowNum) -> new DownloadState(
            rs.getString("lecture_id"),
            rs.getString("status"),
            rs.getObject("bytes_downloaded") == null ? null : rs.getLong("bytes_downloaded"),
            rs.getObject("bytes_total") == null ? null : rs.getLong("bytes_total"),
            rs.getObject("percent") == null ? null : rs.getDouble("percent"),
            rs.getObject("speed_bps") == null ? null : rs.getDouble("speed_bps"),
            rs.getObject("eta_seconds") == null ? null : rs.getLong("eta_seconds"),
            rs.getString("error"),
            Instant.parse(rs.getString("updated_at")));

    public void save(DownloadState state) {
        jdbc.update("""
                INSERT INTO download_state (lecture_id, status, bytes_downloaded, bytes_total,
                        percent, speed_bps, eta_seconds, error, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(lecture_id) DO UPDATE SET
                        status = excluded.status,
                        bytes_downloaded = excluded.bytes_downloaded,
                        bytes_total = excluded.bytes_total,
                        percent = excluded.percent,
                        speed_bps = excluded.speed_bps,
                        eta_seconds = excluded.eta_seconds,
                        error = excluded.error,
                        updated_at = excluded.updated_at
                """, state.lectureId(), state.status(), state.bytesDownloaded(), state.bytesTotal(),
                state.percent(), state.speedBps(), state.etaSeconds(), state.error(),
                state.updatedAt().toString());
    }

    public Optional<DownloadState> find(String lectureId) {
        List<DownloadState> list = jdbc.query(
                "SELECT * FROM download_state WHERE lecture_id = ?", MAPPER, lectureId);
        return list.stream().findFirst();
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM download_state WHERE lecture_id = ?", lectureId);
    }
}
