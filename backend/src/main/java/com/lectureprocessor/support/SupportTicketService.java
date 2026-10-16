package com.lectureprocessor.support;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * Ticket service: creation, lookup, search, message persistence, status
 * transitions and per-message ratings. Every read/write validates the caller's
 * {@link SupportPrincipal} against the stored scope/owner.
 */
@Service
public class SupportTicketService {

    private static final int MAX_SUBJECT = 200;

    private final SupportTicketRepository tickets;
    private final SupportMessageRepository messages;
    private final SupportToolCallRepository toolCalls;
    private final SupportAccessService access;
    private final SupportProperties properties;

    public SupportTicketService(SupportTicketRepository tickets, SupportMessageRepository messages,
            SupportToolCallRepository toolCalls, SupportAccessService access, SupportProperties properties) {
        this.tickets = tickets;
        this.messages = messages;
        this.toolCalls = toolCalls;
        this.access = access;
        this.properties = properties;
    }

    public SupportTicket create(String subject, String category, String lectureId,
            SupportPrincipal principal) {
        requireEnabled();
        SupportPrincipal who = principal == null ? access.installPrincipal() : principal;
        String cleanSubject = (subject == null || subject.isBlank())
                ? "Обращение в поддержку" : subject.strip();
        if (cleanSubject.length() > MAX_SUBJECT) {
            cleanSubject = cleanSubject.substring(0, MAX_SUBJECT);
        }
        Instant now = Instant.now();
        SupportTicket ticket = new SupportTicket(null, who.scope(), who.owner(), blankToNull(lectureId),
                cleanSubject, SupportCategory.from(category), SupportTicketStatus.OPEN, now, now, null);
        return tickets.save(ticket);
    }

    /** Finds an open ticket for a lecture or creates one (lecture-page shortcut). */
    public SupportTicket getOrCreateForLecture(String lectureId, SupportPrincipal principal) {
        requireEnabled();
        SupportPrincipal who = principal == null ? access.installPrincipal() : principal;
        if (lectureId == null || lectureId.isBlank()) {
            return create("Обращение в поддержку", "GENERAL", null, who);
        }
        SupportTicket existing = tickets.findByScope(who.scope(), null, null, 200).stream()
                .filter(ticket -> lectureId.equals(ticket.lectureId()))
                .filter(ticket -> !ticket.status().terminal())
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        return create("Помощь по лекции " + lectureId, "GENERAL", lectureId, who);
    }

    public SupportTicket get(long ticketId, SupportPrincipal principal) {
        SupportTicket ticket = tickets.findById(ticketId).orElseThrow(
                () -> new SupportRequestException("Тикет не найден: " + ticketId));
        access.assertCanAccess(ticket, principal);
        return ticket;
    }

    public List<SupportTicket> list(SupportPrincipal principal, String status, String category, int limit) {
        SupportPrincipal who = principal == null ? access.installPrincipal() : principal;
        return tickets.findByScope(who.scope(), blankToNull(status), blankToNull(category), limit);
    }

    public List<SupportTicket> search(SupportPrincipal principal, String query, String category, int limit) {
        SupportPrincipal who = principal == null ? access.installPrincipal() : principal;
        return tickets.search(who.scope(), query, blankToNull(category), limit);
    }

    public SupportTicket updateStatus(long ticketId, SupportTicketStatus status, SupportPrincipal principal) {
        SupportTicket ticket = get(ticketId, principal);
        return tickets.save(ticket.withStatus(status));
    }

    public SupportTicket close(long ticketId, SupportPrincipal principal) {
        return updateStatus(ticketId, SupportTicketStatus.CLOSED, principal);
    }

    public SupportTicket resolve(long ticketId, SupportPrincipal principal) {
        return updateStatus(ticketId, SupportTicketStatus.RESOLVED, principal);
    }

    public List<SupportMessage> history(long ticketId, SupportPrincipal principal) {
        get(ticketId, principal);
        return messages.findByTicket(ticketId);
    }

    /** Persists a message after checking the ticket scope. */
    public SupportMessage saveMessage(long ticketId, SupportMessage message, SupportPrincipal principal) {
        SupportTicket ticket = get(ticketId, principal);
        SupportMessage scoped = new SupportMessage(message.id(), ticket.id(), message.role(),
                message.content(), message.createdAt(), message.lectureId(), message.jobId(),
                message.citations(), message.tools(), message.provider(), message.model(),
                message.grounded(), message.durationMs(), message.inputTokens(), message.outputTokens(),
                message.estimatedCost(), message.costCurrency(), message.toolCalls(),
                message.toolFailures(), message.feedback());
        SupportMessage saved = messages.save(scoped);
        tickets.save(ticket.touch());
        return saved;
    }

    public void recordToolCalls(List<SupportToolCall> calls) {
        for (SupportToolCall call : calls) {
            toolCalls.save(call);
        }
    }

    /** Stores "помогло / не помогло" for an assistant message, if it is in scope. */
    public SupportMessage rate(long messageId, Integer rating, SupportPrincipal principal) {
        SupportMessage message = messages.findById(messageId).orElseThrow(
                () -> new SupportRequestException("Сообщение не найдено: " + messageId));
        SupportTicket ticket = tickets.findById(message.ticketId()).orElseThrow(
                () -> new SupportRequestException("Тикет не найден"));
        access.assertCanAccess(ticket, principal);
        messages.updateFeedback(messageId, rating);
        return messages.findById(messageId).orElseThrow();
    }

    public List<SupportToolCall> toolTrace(long ticketId, SupportPrincipal principal) {
        get(ticketId, principal);
        return toolCalls.findByTicket(ticketId);
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new SupportRequestException("Поддержка отключена (app.support.enabled=false)");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
