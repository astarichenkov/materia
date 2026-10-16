package com.lectureprocessor.support;

import java.util.List;

/**
 * Final grounded support answer returned to the UI/API.
 *
 * @param grounded     true when at least one confirmed evidence source backs the answer
 * @param limitations  explicit gaps/limitations (never silent)
 */
public record SupportAnswer(
        String answer,
        List<String> suggestedActions,
        List<SupportCitation> citations,
        List<SupportToolTrace> toolsUsed,
        Long ticketId,
        Long messageId,
        String lectureId,
        String jobId,
        String provider,
        String model,
        boolean grounded,
        List<String> limitations,
        String error,
        long durationMs,
        Integer inputTokens,
        Integer outputTokens,
        Double estimatedCost,
        String costCurrency,
        boolean cloud) {
}
