package com.lectureprocessor.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.pipeline.JobState;
import com.lectureprocessor.pipeline.PipelineRun;
import com.lectureprocessor.pipeline.PipelineStage;

/**
 * Persistence of per-run progress. Checkpoints remain the source of truth for
 * artifact validity; runs only record the plan and how far that plan got.
 */
@Repository
public class PipelineRunRepository {

    private final JdbcTemplate jdbc;

    public PipelineRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PipelineRun> MAPPER = (ResultSet rs, int rowNum) -> new PipelineRun(
            rs.getString("id"),
            rs.getString("lecture_id"),
            JobState.valueOf(rs.getString("state")),
            parseStages(rs.getString("planned_stages")),
            rs.getString("current_stage") == null ? null : PipelineStage.valueOf(rs.getString("current_stage")),
            rs.getString("message"),
            rs.getString("error"),
            rs.getInt("stage_done"),
            rs.getInt("stage_total"),
            Instant.parse(rs.getString("created_at")),
            rs.getString("started_at") == null ? null : Instant.parse(rs.getString("started_at")),
            rs.getString("finished_at") == null ? null : Instant.parse(rs.getString("finished_at")));

    private static List<PipelineStage> parseStages(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(PipelineStage::valueOf)
                .toList();
    }

    private static String encodeStages(List<PipelineStage> stages) {
        return stages == null ? "" : stages.stream().map(Enum::name)
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    public String create(String lectureId, List<PipelineStage> plannedStages) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO pipeline_run (id, lecture_id, state, planned_stages, message,
                        stage_done, stage_total, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, lectureId, JobState.QUEUED.name(), encodeStages(plannedStages),
                "Новый запуск", 0, plannedStages == null ? 0 : plannedStages.size(), now);
        return id;
    }

    public void setPlan(String runId, List<PipelineStage> plannedStages) {
        jdbc.update("""
                UPDATE pipeline_run SET planned_stages = ?, stage_total = ?
                WHERE id = ?
                """, encodeStages(plannedStages), plannedStages == null ? 0 : plannedStages.size(), runId);
    }

    public void start(String runId) {
        jdbc.update("""
                UPDATE pipeline_run SET state = ?, started_at = ?, message = ?
                WHERE id = ?
                """, JobState.RUNNING.name(), Instant.now().toString(), "Выполняется", runId);
    }

    public void currentStage(String runId, PipelineStage stage, int done, int total, String message) {
        jdbc.update("""
                UPDATE pipeline_run
                SET current_stage = ?, stage_done = ?, stage_total = ?, message = ?
                WHERE id = ?
                """, stage == null ? null : stage.name(), done, total, message, runId);
    }

    public void message(String runId, String message) {
        jdbc.update("UPDATE pipeline_run SET message = ? WHERE id = ?", message, runId);
    }

    public void finish(String runId, JobState state, String message, String error) {
        jdbc.update("""
                UPDATE pipeline_run
                SET state = ?, message = ?, error = ?, current_stage = NULL, finished_at = ?
                WHERE id = ?
                """, state.name(), message, error, Instant.now().toString(), runId);
    }

    /** A RUNNING run without a live worker (application restart) is interrupted. */
    public int interruptRunning(String lectureId) {
        return jdbc.update("""
                UPDATE pipeline_run
                SET state = ?, message = ?, error = ?, current_stage = NULL, finished_at = ?
                WHERE lecture_id = ? AND state IN (?, ?)
                """, JobState.INTERRUPTED.name(), "Запуск прерван перезапуском приложения",
                "Запуск прерван перезапуском приложения", Instant.now().toString(), lectureId,
                JobState.QUEUED.name(), JobState.RUNNING.name());
    }

    public Optional<PipelineRun> findLatest(String lectureId) {
        List<PipelineRun> rows = jdbc.query("""
                SELECT * FROM pipeline_run WHERE lecture_id = ?
                ORDER BY created_at DESC LIMIT 1
                """, MAPPER, lectureId);
        return rows.stream().findFirst();
    }

    public Optional<PipelineRun> findActive(String lectureId) {
        List<PipelineRun> rows = jdbc.query("""
                SELECT * FROM pipeline_run WHERE lecture_id = ? AND state IN (?, ?)
                ORDER BY created_at DESC LIMIT 1
                """, MAPPER, lectureId, JobState.QUEUED.name(), JobState.RUNNING.name());
        return rows.stream().findFirst();
    }

    public Optional<PipelineRun> find(String runId) {
        List<PipelineRun> rows = jdbc.query("SELECT * FROM pipeline_run WHERE id = ?", MAPPER, runId);
        return rows.stream().findFirst();
    }

    public List<PipelineRun> findRecent(String lectureId, int limit) {
        return jdbc.query("""
                SELECT * FROM pipeline_run WHERE lecture_id = ?
                ORDER BY created_at DESC LIMIT ?
                """, MAPPER, lectureId, limit);
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM pipeline_run WHERE lecture_id = ?", lectureId);
    }

    /** Human label used by the UI; never persisted. */
    public static String stateLabel(JobState state) {
        if (state == null) {
            return "";
        }
        return state.name().toLowerCase(Locale.ROOT);
    }
}
