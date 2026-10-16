package com.lectureprocessor.support;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Persistence for support conversation messages and their metadata. */
@Repository
public class SupportMessageRepository {

    private static final TypeReference<List<SupportCitation>> CITATIONS = new TypeReference<>() {
    };
    private static final TypeReference<List<SupportToolTrace>> TRACES = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SupportMessageRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<SupportMessage> mapper = (ResultSet rs, int rowNum) -> new SupportMessage(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            SupportMessageRole.valueOf(rs.getString("role")),
            rs.getString("content"),
            Instant.parse(rs.getString("created_at")),
            rs.getString("lecture_id"),
            rs.getString("job_id"),
            read(rs.getString("citations_json"), CITATIONS),
            read(rs.getString("tools_json"), TRACES),
            rs.getString("provider"),
            rs.getString("model"),
            rs.getObject("grounded") == null ? null : rs.getInt("grounded") == 1,
            rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
            rs.getObject("input_tokens") == null ? null : rs.getInt("input_tokens"),
            rs.getObject("output_tokens") == null ? null : rs.getInt("output_tokens"),
            rs.getObject("estimated_cost") == null ? null : rs.getDouble("estimated_cost"),
            rs.getString("cost_currency"),
            rs.getObject("tool_calls") == null ? null : rs.getInt("tool_calls"),
            rs.getObject("tool_failures") == null ? null : rs.getInt("tool_failures"),
            rs.getObject("feedback") == null ? null : rs.getInt("feedback"));

    public SupportMessage save(SupportMessage message) {
        if (message.id() == null) {
            jdbc.update("""
                    INSERT INTO support_message (ticket_id, role, content, created_at, lecture_id, job_id,
                            citations_json, tools_json, provider, model, grounded, duration_ms,
                            input_tokens, output_tokens, estimated_cost, cost_currency, tool_calls,
                            tool_failures, feedback)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    message.ticketId(), message.role().name(), message.content(),
                    message.createdAt().toString(), message.lectureId(), message.jobId(),
                    write(message.citations()), write(message.tools()), message.provider(),
                    message.model(), message.grounded() == null ? null : (message.grounded() ? 1 : 0),
                    message.durationMs(), message.inputTokens(), message.outputTokens(),
                    message.estimatedCost(), message.costCurrency(), message.toolCalls(),
                    message.toolFailures(), message.feedback());
            Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            return new SupportMessage(id, message.ticketId(), message.role(), message.content(),
                    message.createdAt(), message.lectureId(), message.jobId(), message.citations(),
                    message.tools(), message.provider(), message.model(), message.grounded(),
                    message.durationMs(), message.inputTokens(), message.outputTokens(),
                    message.estimatedCost(), message.costCurrency(), message.toolCalls(),
                    message.toolFailures(), message.feedback());
        }
        jdbc.update("""
                UPDATE support_message
                SET content = ?, grounded = ?, feedback = ?
                WHERE id = ?
                """, message.content(), message.grounded() == null ? null : (message.grounded() ? 1 : 0),
                message.feedback(), message.id());
        return message;
    }

    public Optional<SupportMessage> findById(long id) {
        List<SupportMessage> list = jdbc.query("SELECT * FROM support_message WHERE id = ?", mapper, id);
        return list.stream().findFirst();
    }

    public List<SupportMessage> findByTicket(long ticketId) {
        return jdbc.query("SELECT * FROM support_message WHERE ticket_id = ? ORDER BY id", mapper, ticketId);
    }

    public List<SupportMessage> findRecentByTicket(long ticketId, int limit) {
        List<SupportMessage> all = jdbc.query(
                "SELECT * FROM support_message WHERE ticket_id = ? ORDER BY id DESC LIMIT ?", mapper,
                ticketId, Math.max(1, limit));
        List<SupportMessage> ordered = new java.util.ArrayList<>(all);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    public int countByTicket(long ticketId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_message WHERE ticket_id = ?", Integer.class, ticketId);
        return count == null ? 0 : count;
    }

    public void updateFeedback(long messageId, Integer feedback) {
        jdbc.update("UPDATE support_message SET feedback = ? WHERE id = ?", feedback, messageId);
    }

    public SupportMetrics.MessageAggregate aggregate() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN grounded = 1 THEN 1 ELSE 0 END), 0) AS grounded,
                       COALESCE(SUM(tool_calls), 0) AS tool_calls,
                       COALESCE(SUM(tool_failures), 0) AS tool_failures,
                       COALESCE(AVG(duration_ms), 0) AS avg_duration,
                       COALESCE(SUM(CASE WHEN feedback = 1 THEN 1 ELSE 0 END), 0) AS helped,
                       COALESCE(SUM(CASE WHEN feedback = -1 THEN 1 ELSE 0 END), 0) AS not_helped,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens
                FROM support_message WHERE role = 'ASSISTANT'
                """,
                (rs, rowNum) -> new SupportMetrics.MessageAggregate(
                        rs.getInt("total"), rs.getInt("grounded"), rs.getLong("tool_calls"),
                        rs.getLong("tool_failures"), rs.getDouble("avg_duration"),
                        rs.getInt("helped"), rs.getInt("not_helped"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens")));
    }

    public java.util.Map<String, Integer> providerBreakdown() {
        java.util.Map<String, Integer> result = new java.util.LinkedHashMap<>();
        jdbc.query("""
                SELECT COALESCE(provider, 'unknown') AS provider, COUNT(*) AS total
                FROM support_message WHERE role = 'ASSISTANT'
                GROUP BY provider ORDER BY total DESC
                """, rs -> {
            result.put(rs.getString("provider"), rs.getInt("total"));
        });
        return result;
    }

    private String write(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
