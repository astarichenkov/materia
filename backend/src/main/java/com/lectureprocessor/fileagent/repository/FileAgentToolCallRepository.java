package com.lectureprocessor.fileagent.repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.fileagent.model.FileAgentToolCall;

/** Persistence for the bounded file-agent tool trace. */
@Repository
public class FileAgentToolCallRepository {

    private final JdbcTemplate jdbc;

    public FileAgentToolCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<FileAgentToolCall> mapper = (ResultSet rs, int rowNum) ->
            new FileAgentToolCall(rs.getLong("id"), rs.getString("task_id"),
                    rs.getString("tool_name"), rs.getString("arguments"), rs.getInt("ok") == 1,
                    rs.getLong("duration_ms"), rs.getString("summary"),
                    Instant.parse(rs.getString("created_at")));

    public void insert(FileAgentToolCall call) {
        jdbc.update("""
                INSERT INTO file_agent_tool_call (task_id, tool_name, arguments, ok, duration_ms,
                        summary, created_at)
                VALUES (?,?,?,?,?,?,?)
                """, call.taskId(), call.toolName(), call.arguments(), call.ok() ? 1 : 0,
                call.durationMs(), call.summary(), call.createdAt().toString());
    }

    public List<FileAgentToolCall> findByTask(String taskId) {
        return jdbc.query("SELECT * FROM file_agent_tool_call WHERE task_id = ? ORDER BY id",
                mapper, taskId);
    }
}
