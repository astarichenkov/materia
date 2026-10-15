package com.lectureprocessor.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.pipeline.JobState;
import com.lectureprocessor.pipeline.PipelineJob;
import com.lectureprocessor.pipeline.PipelineStage;

@Repository
public class PipelineJobRepository {

    private final JdbcTemplate jdbc;

    public PipelineJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PipelineJob> MAPPER = (ResultSet rs, int rowNum) -> new PipelineJob(
            rs.getString("lecture_id"),
            JobState.valueOf(rs.getString("state")),
            rs.getString("current_stage") == null ? null : PipelineStage.valueOf(rs.getString("current_stage")),
            rs.getInt("attempts"),
            rs.getString("lease_owner"),
            rs.getString("lease_until") == null ? null : Instant.parse(rs.getString("lease_until")),
            rs.getString("error"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at")));

    public void ensure(String lectureId) {
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO pipeline_job (lecture_id, state, attempts, created_at, updated_at)
                VALUES (?,?,0,?,?)
                ON CONFLICT(lecture_id) DO NOTHING
                """, lectureId, JobState.QUEUED.name(), now, now);
    }

    public Optional<PipelineJob> find(String lectureId) {
        List<PipelineJob> list = jdbc.query(
                "SELECT * FROM pipeline_job WHERE lecture_id = ?", MAPPER, lectureId);
        return list.stream().findFirst();
    }

    public List<PipelineJob> findAll() {
        return jdbc.query("SELECT * FROM pipeline_job ORDER BY updated_at DESC", MAPPER);
    }

    /**
     * Atomically claim the job for {@code owner} until {@code leaseUntil}. Succeeds
     * when the job is not actively leased (another run, a previous crashed run
     * whose lease expired, or a terminal retryable state).
     *
     * @return true if this caller now owns the job
     */
    public boolean claim(String lectureId, String owner, Instant leaseUntil) {
        ensure(lectureId);
        String now = Instant.now().toString();
        int updated = jdbc.update("""
                UPDATE pipeline_job
                SET state = ?, lease_owner = ?, lease_until = ?, updated_at = ?,
                    attempts = attempts + 1, error = NULL
                WHERE lecture_id = ?
                  AND (lease_owner IS NULL OR lease_owner = ? OR lease_until IS NULL
                       OR lease_until < ? OR state NOT IN (?, ?))
                """,
                JobState.RUNNING.name(), owner, leaseUntil.toString(), now, lectureId, owner,
                now, JobState.RUNNING.name(), JobState.RUNNING.name());
        return updated > 0;
    }

    public void renewLease(String lectureId, String owner, Instant leaseUntil) {
        jdbc.update("""
                UPDATE pipeline_job SET lease_until = ?, updated_at = ?
                WHERE lecture_id = ? AND lease_owner = ?
                """, leaseUntil.toString(), Instant.now().toString(), lectureId, owner);
    }

    public void updateProgress(String lectureId, String owner, PipelineStage stage) {
        jdbc.update("""
                UPDATE pipeline_job SET current_stage = ?, updated_at = ?
                WHERE lecture_id = ? AND lease_owner = ?
                """, stage == null ? null : stage.name(), Instant.now().toString(), lectureId, owner);
    }

    public void finish(String lectureId, JobState state, String error) {
        jdbc.update("""
                UPDATE pipeline_job
                SET state = ?, error = ?, lease_owner = NULL, lease_until = NULL, updated_at = ?
                WHERE lecture_id = ?
                """, state.name(), error, Instant.now().toString(), lectureId);
    }

    /** Lease-expired RUNNING jobs become INTERRUPTED, keeping their checkpoints. */
    public int interruptExpired() {
        String now = Instant.now().toString();
        return jdbc.update("""
                UPDATE pipeline_job
                SET state = ?, lease_owner = NULL, lease_until = NULL, updated_at = ?
                WHERE state = ? AND (lease_until IS NULL OR lease_until < ?)
                """, JobState.INTERRUPTED.name(), now, JobState.RUNNING.name(), now);
    }

    /**
     * On startup a single-instance app knows no job can still be running, so all
     * RUNNING jobs become INTERRUPTED (retryable) without losing checkpoints.
     */
    public int interruptAllRunning() {
        return jdbc.update("""
                UPDATE pipeline_job
                SET state = ?, lease_owner = NULL, lease_until = NULL, updated_at = ?
                WHERE state = ?
                """, JobState.INTERRUPTED.name(), Instant.now().toString(), JobState.RUNNING.name());
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM pipeline_job WHERE lecture_id = ?", lectureId);
    }
}
