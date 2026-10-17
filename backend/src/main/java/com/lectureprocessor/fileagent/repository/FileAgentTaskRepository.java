package com.lectureprocessor.fileagent.repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.fileagent.FileAgentTaskStatus;
import com.lectureprocessor.fileagent.FileWorkspace;
import com.lectureprocessor.fileagent.model.FileAgentTask;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Persistence for file-agent tasks and their metrics. */
@Repository
public class FileAgentTaskRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public FileAgentTaskRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<FileAgentTask> mapper = (ResultSet rs, int rowNum) -> new FileAgentTask(
            rs.getString("id"),
            FileWorkspace.valueOf(rs.getString("workspace")),
            rs.getString("lecture_id"),
            rs.getString("goal"),
            FileAgentTaskStatus.valueOf(rs.getString("status")),
            rs.getString("plan"),
            readStrings(rs.getString("relevant_files")),
            rs.getString("provider"),
            rs.getString("model"),
            rs.getString("error"),
            rs.getString("result_summary"),
            rs.getInt("files_searched"),
            rs.getInt("files_read"),
            rs.getInt("files_proposed"),
            rs.getInt("files_changed"),
            rs.getInt("tool_calls"),
            rs.getInt("tool_failures"),
            rs.getLong("planning_ms"),
            rs.getLong("duration_ms"),
            integer(rs, "input_tokens"),
            integer(rs, "output_tokens"),
            decimal(rs, "estimated_cost"),
            rs.getString("cost_currency"),
            rs.getInt("rollback_attempts"),
            rs.getString("verification_status"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at")),
            instant(rs.getString("completed_at")));

    public FileAgentTask insert(FileAgentTask task) {
        jdbc.update("""
                INSERT INTO file_agent_task (id, workspace, lecture_id, goal, status, plan,
                        relevant_files, provider, model, error, result_summary, files_searched,
                        files_read, files_proposed, files_changed, tool_calls, tool_failures,
                        planning_ms, duration_ms, input_tokens, output_tokens, estimated_cost,
                        cost_currency, rollback_attempts, verification_status, created_at,
                        updated_at, completed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                task.id(), task.workspace().name(), task.lectureId(), task.goal(),
                task.status().name(), task.plan(), writeStrings(task.relevantFiles()),
                task.provider(), task.model(), task.error(), task.resultSummary(),
                task.filesSearched(), task.filesRead(), task.filesProposed(), task.filesChanged(),
                task.toolCalls(), task.toolFailures(), task.planningMs(), task.durationMs(),
                task.inputTokens(), task.outputTokens(), task.estimatedCost(), task.costCurrency(),
                task.rollbackAttempts(), task.verificationStatus(), task.createdAt().toString(),
                task.updatedAt().toString(), instantText(task.completedAt()));
        return task;
    }

    public void update(FileAgentTask task) {
        jdbc.update("""
                UPDATE file_agent_task SET
                    status = ?, plan = ?, relevant_files = ?, provider = ?, model = ?, error = ?,
                    result_summary = ?, files_searched = ?, files_read = ?, files_proposed = ?,
                    files_changed = ?, tool_calls = ?, tool_failures = ?, planning_ms = ?,
                    duration_ms = ?, input_tokens = ?, output_tokens = ?, estimated_cost = ?,
                    cost_currency = ?, rollback_attempts = ?, verification_status = ?,
                    updated_at = ?, completed_at = ?
                WHERE id = ?
                """,
                task.status().name(), task.plan(), writeStrings(task.relevantFiles()),
                task.provider(), task.model(), task.error(), task.resultSummary(),
                task.filesSearched(), task.filesRead(), task.filesProposed(), task.filesChanged(),
                task.toolCalls(), task.toolFailures(), task.planningMs(), task.durationMs(),
                task.inputTokens(), task.outputTokens(), task.estimatedCost(), task.costCurrency(),
                task.rollbackAttempts(), task.verificationStatus(), task.updatedAt().toString(),
                instantText(task.completedAt()), task.id());
    }

    public Optional<FileAgentTask> findById(String id) {
        return jdbc.query("SELECT * FROM file_agent_task WHERE id = ?", mapper, id)
                .stream().findFirst();
    }

    public List<FileAgentTask> list(int limit) {
        return jdbc.query("SELECT * FROM file_agent_task ORDER BY created_at DESC LIMIT ?", mapper,
                Math.max(1, Math.min(limit, 200)));
    }

    public List<FileAgentTask> findByStatus(FileAgentTaskStatus status, int limit) {
        return jdbc.query("SELECT * FROM file_agent_task WHERE status = ? ORDER BY created_at DESC LIMIT ?",
                mapper, status.name(), Math.max(1, Math.min(limit, 200)));
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM file_agent_task", Integer.class);
        return count == null ? 0 : count;
    }

    public int countByStatus(FileAgentTaskStatus status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM file_agent_task WHERE status = ?", Integer.class, status.name());
        return count == null ? 0 : count;
    }

    public long sumToolCalls() {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(tool_calls),0) FROM file_agent_task",
                Long.class);
        return value == null ? 0 : value;
    }

    public long sumToolFailures() {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(tool_failures),0) FROM file_agent_task",
                Long.class);
        return value == null ? 0 : value;
    }

    public long sumFilesChanged() {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(files_changed),0) FROM file_agent_task",
                Long.class);
        return value == null ? 0 : value;
    }

    public long sumRollbacks() {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(rollback_attempts),0) FROM file_agent_task",
                Long.class);
        return value == null ? 0 : value;
    }

    public void recoverInterruptedTasks(String message) {
        jdbc.update("""
                UPDATE file_agent_task
                SET status = 'FAILED', error = ?, updated_at = ?, completed_at = ?
                WHERE status IN ('CREATED','PLANNING','SEARCHING','ANALYZING','PROPOSING',
                                 'APPROVED','APPLYING','VERIFYING')
                """, message, Instant.now().toString(), Instant.now().toString());
    }

    private String writeStrings(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (RuntimeException e) {
            return "[]";
        }
    }

    private List<String> readStrings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRINGS);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static Integer integer(ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : rs.getInt(column);
    }

    private static Double decimal(ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : rs.getDouble(column);
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static String instantText(Instant value) {
        return value == null ? null : value.toString();
    }
}
