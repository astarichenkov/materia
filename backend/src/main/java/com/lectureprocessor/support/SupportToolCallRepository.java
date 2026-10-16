package com.lectureprocessor.support;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Persistence for the bounded trace of MCP tool calls made for an answer. */
@Repository
public class SupportToolCallRepository {

    private final JdbcTemplate jdbc;

    public SupportToolCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SupportToolCall> MAPPER = (ResultSet rs, int rowNum) -> new SupportToolCall(
            rs.getLong("id"),
            rs.getObject("message_id") == null ? null : rs.getLong("message_id"),
            rs.getObject("ticket_id") == null ? null : rs.getLong("ticket_id"),
            rs.getString("tool_name"),
            rs.getInt("ok") == 1,
            rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
            rs.getString("summary"),
            Instant.parse(rs.getString("created_at")));

    public SupportToolCall save(SupportToolCall call) {
        jdbc.update("""
                INSERT INTO support_tool_call (message_id, ticket_id, tool_name, ok, duration_ms,
                        summary, created_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                call.messageId(), call.ticketId(), call.toolName(), call.ok() ? 1 : 0,
                call.durationMs(), call.summary(), call.createdAt().toString());
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return new SupportToolCall(id, call.messageId(), call.ticketId(), call.toolName(), call.ok(),
                call.durationMs(), call.summary(), call.createdAt());
    }

    public List<SupportToolCall> findByTicket(long ticketId) {
        return jdbc.query("SELECT * FROM support_tool_call WHERE ticket_id = ? ORDER BY id", MAPPER,
                ticketId);
    }

    public int countFailures() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_tool_call WHERE ok = 0", Integer.class);
        return count == null ? 0 : count;
    }
}
