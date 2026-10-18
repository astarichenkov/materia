package com.lectureprocessor.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.pipeline.ArtifactManifest;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.pipeline.StageCheckpoint;
import com.lectureprocessor.pipeline.StageState;

import tools.jackson.databind.ObjectMapper;

@Repository
public class StageCheckpointRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public StageCheckpointRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<StageCheckpoint> mapper = (ResultSet rs, int rowNum) -> new StageCheckpoint(
            rs.getString("lecture_id"),
            PipelineStage.valueOf(rs.getString("stage")),
            StageState.valueOf(rs.getString("state")),
            rs.getString("fingerprint"),
            rs.getInt("attempt"),
            rs.getString("started_at") == null ? null : Instant.parse(rs.getString("started_at")),
            rs.getString("finished_at") == null ? null : Instant.parse(rs.getString("finished_at")),
            rs.getString("error"),
            readManifest(rs.getString("artifact_manifest_json")));

    public Optional<StageCheckpoint> find(String lectureId, PipelineStage stage) {
        List<StageCheckpoint> list = jdbc.query(
                "SELECT * FROM stage_checkpoint WHERE lecture_id = ? AND stage = ?", mapper, lectureId,
                stage.name());
        return list.stream().findFirst();
    }

    public List<StageCheckpoint> findByLecture(String lectureId) {
        return jdbc.query("SELECT * FROM stage_checkpoint WHERE lecture_id = ? ORDER BY rowid", mapper,
                lectureId);
    }

    public void start(String lectureId, PipelineStage stage, String fingerprint, int attempt) {
        jdbc.update("""
                INSERT INTO stage_checkpoint (lecture_id, stage, state, fingerprint, attempt,
                        started_at, finished_at, error, artifact_manifest_json)
                VALUES (?,?,?,?,?,?,NULL,NULL,NULL)
                ON CONFLICT(lecture_id, stage) DO UPDATE SET
                        state = excluded.state,
                        fingerprint = excluded.fingerprint,
                        attempt = excluded.attempt,
                        started_at = excluded.started_at,
                        finished_at = NULL,
                        error = NULL,
                        artifact_manifest_json = NULL
                """, lectureId, stage.name(), StageState.RUNNING.name(), fingerprint, attempt,
                Instant.now().toString());
    }

    public void complete(String lectureId, PipelineStage stage, ArtifactManifest manifest) {
        jdbc.update("""
                UPDATE stage_checkpoint
                SET state = ?, finished_at = ?, error = NULL, artifact_manifest_json = ?
                WHERE lecture_id = ? AND stage = ?
                """, StageState.COMPLETED.name(), Instant.now().toString(), writeManifest(manifest),
                lectureId, stage.name());
    }

    public void fail(String lectureId, PipelineStage stage, String error) {
        jdbc.update("""
                UPDATE stage_checkpoint
                SET state = ?, finished_at = ?, error = ?
                WHERE lecture_id = ? AND stage = ?
                """, StageState.FAILED.name(), Instant.now().toString(), error, lectureId, stage.name());
    }

    public void cancel(String lectureId, PipelineStage stage) {
        jdbc.update("""
                UPDATE stage_checkpoint
                SET state = ?, finished_at = ?, error = ?
                WHERE lecture_id = ? AND stage = ?
                """, StageState.CANCELLED.name(), Instant.now().toString(), "Отменено пользователем",
                lectureId, stage.name());
    }

    /**
     * Moves every stage left in RUNNING for this lecture to CANCELLED. A job that
     * was cancelled must not leave an active-looking stage behind.
     */
    public int cancelRunning(String lectureId) {
        return jdbc.update("""
                UPDATE stage_checkpoint
                SET state = ?, finished_at = ?, error = ?
                WHERE lecture_id = ? AND state = ?
                """, StageState.CANCELLED.name(), Instant.now().toString(), "Отменено пользователем",
                lectureId, StageState.RUNNING.name());
    }

    /**
     * Startup recovery: because the process just started, no stage can still be
     * executing, so RUNNING checkpoints become INTERRUPTED (retryable).
     */
    public int interruptAllRunning() {
        return jdbc.update("""
                UPDATE stage_checkpoint
                SET state = ?, finished_at = ?, error = ?
                WHERE state = ?
                """, StageState.INTERRUPTED.name(), Instant.now().toString(),
                "Обработка прервана перезапуском приложения", StageState.RUNNING.name());
    }

    /**
     * Invalidate a stage and every stage that comes after it (dependents), so a
     * changed input re-runs downstream work but preserves upstream artifacts.
     */
    public int invalidateFrom(String lectureId, PipelineStage stage) {
        int index = stage.ordinal();
        List<String> names = java.util.Arrays.stream(PipelineStage.values())
                .skip(index)
                .map(Enum::name)
                .toList();
        int removed = 0;
        for (String name : names) {
            removed += jdbc.update("DELETE FROM stage_checkpoint WHERE lecture_id = ? AND stage = ?",
                    lectureId, name);
        }
        return removed;
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM stage_checkpoint WHERE lecture_id = ?", lectureId);
    }

    private String writeManifest(ArtifactManifest manifest) {
        try {
            return objectMapper.writeValueAsString(manifest == null ? ArtifactManifest.empty() : manifest);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ArtifactManifest readManifest(String json) {
        if (json == null || json.isBlank()) {
            return ArtifactManifest.empty();
        }
        try {
            return objectMapper.readValue(json, ArtifactManifest.class);
        } catch (RuntimeException e) {
            return ArtifactManifest.empty();
        }
    }
}
