package com.lectureprocessor.support;

import java.time.Instant;

/**
 * A persisted support ticket.
 *
 * <p>{@code scope} and {@code owner} are authorization attributes: access is
 * always decided from them, never from {@code id} alone.</p>
 *
 * @param id         generated id (null before insert)
 * @param scope      installation scope (authorization boundary)
 * @param owner      per-session owner id (used when isolation = session)
 * @param lectureId  optional linked lecture
 * @param subject    short subject
 * @param category   ticket category
 * @param status     lifecycle status
 * @param createdAt  creation time
 * @param updatedAt  last change time
 * @param resolvedAt resolution time (nullable)
 */
public record SupportTicket(
        Long id,
        String scope,
        String owner,
        String lectureId,
        String subject,
        SupportCategory category,
        SupportTicketStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt) {

    public SupportTicket withStatus(SupportTicketStatus newStatus) {
        return new SupportTicket(id, scope, owner, lectureId, subject, category, newStatus,
                createdAt, Instant.now(), newStatus == SupportTicketStatus.RESOLVED
                        ? (resolvedAt == null ? Instant.now() : resolvedAt) : resolvedAt);
    }

    public SupportTicket withSubject(String newSubject) {
        return new SupportTicket(id, scope, owner, lectureId, newSubject, category, status,
                createdAt, Instant.now(), resolvedAt);
    }

    public SupportTicket withLecture(String newLectureId) {
        return new SupportTicket(id, scope, owner, newLectureId, subject, category, status,
                createdAt, Instant.now(), resolvedAt);
    }

    public SupportTicket touch() {
        return new SupportTicket(id, scope, owner, lectureId, subject, category, status,
                createdAt, Instant.now(), resolvedAt);
    }
}
