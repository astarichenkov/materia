package com.lectureprocessor.support;

import org.springframework.stereotype.Service;

/**
 * Aggregates support metrics from persisted rows for the dashboard. Ratings are
 * shown as ratings only — thumbs-up is never presented as diagnostic accuracy.
 */
@Service
public class SupportMetricsService {

    private final SupportTicketRepository tickets;
    private final SupportMessageRepository messages;
    private final SupportToolCallRepository toolCalls;

    public SupportMetricsService(SupportTicketRepository tickets, SupportMessageRepository messages,
            SupportToolCallRepository toolCalls) {
        this.tickets = tickets;
        this.messages = messages;
        this.toolCalls = toolCalls;
    }

    public SupportMetrics metrics(SupportPrincipal principal) {
        String scope = principal == null ? "local-install" : principal.scope();
        int total = tickets.countByScope(scope);
        int open = tickets.countByScopeAndStatus(scope, SupportTicketStatus.OPEN)
                + tickets.countByScopeAndStatus(scope, SupportTicketStatus.IN_PROGRESS);
        int resolved = tickets.countByScopeAndStatus(scope, SupportTicketStatus.RESOLVED)
                + tickets.countByScopeAndStatus(scope, SupportTicketStatus.CLOSED);
        // Message aggregates are installation-wide; ticket counts are scoped.
        SupportMetrics.MessageAggregate aggregate = messages.aggregate();
        return new SupportMetrics(total, open, resolved, aggregate.total(), aggregate.grounded(),
                aggregate.toolCalls(), aggregate.toolFailures(), aggregate.avgDurationMs(),
                aggregate.helped(), aggregate.notHelped(), aggregate.inputTokens(),
                aggregate.outputTokens(), messages.providerBreakdown());
    }
}
