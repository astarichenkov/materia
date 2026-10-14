package com.lectureprocessor.repository;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lightweight per-lecture phase timings (ffmpeg, whisper, llm, export, total),
 * so the benchmark UI can show LLM stage time next to full conversion time.
 */
@Repository
public class TimingRepository {

    public static final String FFMPEG = "ffmpeg";
    public static final String WHISPER = "whisper";
    public static final String LLM = "llm";
    public static final String EXPORT = "export";
    public static final String TOTAL = "total";

    private final JdbcTemplate jdbc;

    public TimingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String lectureId, String stage, long durationMs) {
        jdbc.update("""
                INSERT INTO lecture_stage_timing (lecture_id, stage, duration_ms)
                VALUES (?,?,?)
                ON CONFLICT(lecture_id, stage) DO UPDATE SET duration_ms = excluded.duration_ms
                """, lectureId, stage, durationMs);
    }

    public Map<String, Long> findByLecture(String lectureId) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query("SELECT stage, duration_ms FROM lecture_stage_timing WHERE lecture_id = ?",
                rs -> {
                    result.put(rs.getString("stage"), rs.getLong("duration_ms"));
                }, lectureId);
        return result;
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM lecture_stage_timing WHERE lecture_id = ?", lectureId);
    }
}
