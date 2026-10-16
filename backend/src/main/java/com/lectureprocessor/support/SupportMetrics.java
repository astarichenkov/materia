package com.lectureprocessor.support;

import java.util.Map;

/**
 * Aggregate support metrics for the dashboard. All values are computed from
 * persisted rows — no separate monitoring stack.
 *
 * @param totalTickets   tickets in scope
 * @param openTickets    OPEN + IN_PROGRESS
 * @param resolvedTickets RESOLVED + CLOSED
 * @param messages       assistant answers
 * @param groundedAnswers answers with at least one confirmed evidence source
 * @param toolCalls      total tool invocations
 * @param toolFailures   failed tool invocations
 * @param avgResponseMs  average answer duration
 * @param helped         thumbs-up count (a rating, not an accuracy metric)
 * @param notHelped      thumbs-down count
 * @param inputTokens    total prompt tokens
 * @param outputTokens   total completion tokens
 * @param providerBreakdown answers per provider
 */
public record SupportMetrics(
        int totalTickets,
        int openTickets,
        int resolvedTickets,
        int messages,
        int groundedAnswers,
        long toolCalls,
        long toolFailures,
        double avgResponseMs,
        int helped,
        int notHelped,
        long inputTokens,
        long outputTokens,
        Map<String, Integer> providerBreakdown) {

    public record MessageAggregate(
            int total,
            int grounded,
            long toolCalls,
            long toolFailures,
            double avgDurationMs,
            int helped,
            int notHelped,
            long inputTokens,
            long outputTokens) {
    }
}
