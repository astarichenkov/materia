package com.lectureprocessor.fileagent.repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.fileagent.model.FileAgentExecution;

/** Persistence for observable execution phases. */
@Repository
public class FileAgentExecutionRepository {

    private final JdbcTemplate jdbc;

    public FileAgentExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<FileAgentExecution> mapper = (ResultSet rs, int rowNum) ->
            new FileAgentExecution(rs.getLong("id"), rs.getString("task_id"),
                    rs.getString("phase"), rs.getString("status"), rs.getString("detail"),
                    Instant.parse(rs.getString("started_at")),
                    rs.getString("finished_at") == null ? null : Instant.parse(rs.getString("finished_at")),
                    rs.getLong("duration_ms"));

    public void insert(FileAgentExecution execution) {
        jdbc.update("""
                INSERT INTO file_agent_execution (task_id, phase, status, detail, started_at,
                        finished_at, duration_ms)
                VALUES (?,?,?,?,?,?,?)
                """, execution.taskId(), execution.phase(), execution.status(), execution.detail(),
                execution.startedAt().toString(),
                execution.finishedAt() == null ? null : execution.finishedAt().toString(),
                execution.durationMs());
    }

    public List<FileAgentExecution> findByTask(String taskId) {
        return jdbc.query("SELECT * FROM file_agent_execution WHERE task_id = ? ORDER BY id",
                mapper, taskId);
    }
}
