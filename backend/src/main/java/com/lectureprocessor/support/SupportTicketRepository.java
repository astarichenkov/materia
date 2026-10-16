package com.lectureprocessor.support;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Persistence for support tickets. Scope is always part of every lookup. */
@Repository
public class SupportTicketRepository {

    private final JdbcTemplate jdbc;

    public SupportTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SupportTicket> MAPPER = (ResultSet rs, int rowNum) -> new SupportTicket(
            rs.getLong("id"),
            rs.getString("scope"),
            rs.getString("owner"),
            rs.getString("lecture_id"),
            rs.getString("subject"),
            SupportCategory.from(rs.getString("category")),
            SupportTicketStatus.from(rs.getString("status")),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at")),
            rs.getString("resolved_at") == null ? null : Instant.parse(rs.getString("resolved_at")));

    public SupportTicket save(SupportTicket ticket) {
        if (ticket.id() == null) {
            jdbc.update("""
                    INSERT INTO support_ticket (scope, owner, lecture_id, subject, category, status,
                            created_at, updated_at, resolved_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,
                    ticket.scope(), ticket.owner(), ticket.lectureId(), ticket.subject(),
                    ticket.category().name(), ticket.status().name(), ticket.createdAt().toString(),
                    ticket.updatedAt().toString(), instant(ticket.resolvedAt()));
            Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            return new SupportTicket(id, ticket.scope(), ticket.owner(), ticket.lectureId(),
                    ticket.subject(), ticket.category(), ticket.status(), ticket.createdAt(),
                    ticket.updatedAt(), ticket.resolvedAt());
        }
        jdbc.update("""
                UPDATE support_ticket
                SET lecture_id = ?, subject = ?, category = ?, status = ?, updated_at = ?, resolved_at = ?
                WHERE id = ?
                """,
                ticket.lectureId(), ticket.subject(), ticket.category().name(), ticket.status().name(),
                ticket.updatedAt().toString(), instant(ticket.resolvedAt()), ticket.id());
        return ticket;
    }

    public Optional<SupportTicket> findById(long id) {
        List<SupportTicket> list = jdbc.query("SELECT * FROM support_ticket WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public List<SupportTicket> findByScope(String scope, String status, String category, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM support_ticket WHERE scope = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(scope);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category);
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 200)));
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** Keyword search over subject and the latest message text within a scope. */
    public List<SupportTicket> search(String scope, String query, String category, int limit) {
        if (query == null || query.isBlank()) {
            return findByScope(scope, null, category, limit);
        }
        String like = "%" + query.strip().toLowerCase(java.util.Locale.ROOT) + "%";
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT t.* FROM support_ticket t
                LEFT JOIN support_message m ON m.ticket_id = t.id
                WHERE t.scope = ?
                  AND (LOWER(t.subject) LIKE ? OR LOWER(m.content) LIKE ?)
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(scope);
        args.add(like);
        args.add(like);
        if (category != null && !category.isBlank()) {
            sql.append(" AND t.category = ?");
            args.add(category);
        }
        sql.append(" ORDER BY t.updated_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 100)));
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public int countByScope(String scope) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_ticket WHERE scope = ?", Integer.class, scope);
        return count == null ? 0 : count;
    }

    public int countByScopeAndStatus(String scope, SupportTicketStatus status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_ticket WHERE scope = ? AND status = ?", Integer.class,
                scope, status.name());
        return count == null ? 0 : count;
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM support_message WHERE ticket_id = ?", id);
        jdbc.update("DELETE FROM support_tool_call WHERE ticket_id = ?", id);
        jdbc.update("DELETE FROM support_ticket WHERE id = ?", id);
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}
