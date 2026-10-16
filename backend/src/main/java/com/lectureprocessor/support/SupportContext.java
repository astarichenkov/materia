package com.lectureprocessor.support;

import java.util.List;

/**
 * Aggregated context for one support question: routing decision, evidence,
 * citations, bounded tool trace and explicit limitations.
 *
 * @param ticket              resolved ticket (never null once the service ran)
 * @param lectureId           resolved lecture id (may be null)
 * @param route               routing decision
 * @param evidence            aggregated evidence
 * @param citations           deduplicated citations
 * @param toolTraces          bounded tool trace for the UI/answer
 * @param toolInvocations     persistence records for {@code support_tool_call}
 * @param diagnosticsAvailable whether any persisted job/stage data was found
 * @param grounded            whether confirmed evidence backs the answer
 */
public record SupportContext(
        SupportTicket ticket,
        String lectureId,
        SupportRoute route,
        List<SupportEvidence> evidence,
        List<SupportCitation> citations,
        List<SupportToolTrace> toolTraces,
        List<SupportToolCall> toolInvocations,
        boolean diagnosticsAvailable,
        boolean grounded) {

    public SupportContext {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        citations = citations == null ? List.of() : List.copyOf(citations);
        toolTraces = toolTraces == null ? List.of() : List.copyOf(toolTraces);
        toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
    }
}
