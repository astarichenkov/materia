package com.lectureprocessor.support;

import java.time.Instant;
import java.util.List;

/**
 * One persisted support conversation message.
 *
 * <p>Only answer metadata is stored here: provider/model/tokens for LLM answers,
 * citations and a bounded tool trace. No API keys, cookies or Authorization
 * headers are ever written.</p>
 *
 * @param feedback user rating: 1 = helped, -1 = not helped, null = no rating
 */
public record SupportMessage(
        Long id,
        Long ticketId,
        SupportMessageRole role,
        String content,
        Instant createdAt,
        String lectureId,
        String jobId,
        List<SupportCitation> citations,
        List<SupportToolTrace> tools,
        String provider,
        String model,
        Boolean grounded,
        Long durationMs,
        Integer inputTokens,
        Integer outputTokens,
        Double estimatedCost,
        String costCurrency,
        Integer toolCalls,
        Integer toolFailures,
        Integer feedback) {

    public SupportMessage {
        citations = citations == null ? List.of() : List.copyOf(citations);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
