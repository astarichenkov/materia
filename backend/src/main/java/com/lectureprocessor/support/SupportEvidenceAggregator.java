package com.lectureprocessor.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.lectureprocessor.assistant.DocSearchResult;

/**
 * Merges documentation retrievals and MCP tool results into a single evidence
 * list, keeping confirmed facts and documented information visibly separate.
 * Produces the citations, the bounded tool trace and the persistence records.
 */
@Component
public class SupportEvidenceAggregator {

    /** Aggregated evidence and provenance for one answer. */
    public record AggregatedEvidence(
            List<SupportEvidence> evidence,
            List<SupportCitation> citations,
            List<SupportToolTrace> traces,
            List<SupportToolCall> invocations,
            boolean diagnosticsAvailable,
            boolean grounded) {
    }

    public AggregatedEvidence aggregate(List<DocSearchResult> docs, List<SupportToolCallResult> tools,
            SupportTicket ticket, Long messageId) {
        List<SupportEvidence> evidence = new ArrayList<>();
        List<SupportCitation> citations = new ArrayList<>();
        List<SupportToolTrace> traces = new ArrayList<>();
        List<SupportToolCall> invocations = new ArrayList<>();
        Set<String> seenCitations = new LinkedHashSet<>();
        boolean diagnosticsAvailable = false;

        for (DocSearchResult doc : docs) {
            SupportCitation citation = SupportCitation.doc(
                    doc.chunk().sourcePath() + "#" + doc.chunk().sequence(),
                    doc.chunk().heading() == null ? doc.chunk().sourceTitle() : doc.chunk().heading(),
                    excerpt(doc.chunk().text()), doc.score());
            evidence.add(new SupportEvidence("DOC",
                    citation.source(), doc.chunk().text(), citation));
            if (seenCitations.add(citation.source())) {
                citations.add(citation);
            }
        }
        traces.add(new SupportToolTrace("search_help_docs", true, 0,
                "найдено фрагментов: " + docs.size()));
        invocations.add(new SupportToolCall(null, messageId, ticket == null ? null : ticket.id(),
                "search_help_docs", true, 0L, "docs=" + docs.size(), Instant.now()));

        for (SupportToolCallResult result : tools) {
            String label = labelFor(result.name());
            boolean hasFacts = result.ok() && hasDiagnosticFacts(result);
            if (hasFacts) {
                diagnosticsAvailable = true;
            }
            traces.add(new SupportToolTrace(result.name(), result.ok(), result.durationMs(),
                    truncate(result.text(), 400)));
            invocations.add(new SupportToolCall(null, messageId, ticket == null ? null : ticket.id(),
                    result.name(), result.ok(), result.durationMs(),
                    truncate(result.text(), 600), Instant.now()));
            if (result.ok() && !result.text().isBlank()) {
                SupportCitation citation = SupportCitation.fact(kindFor(result.name()),
                        result.name(), label, truncate(result.text(), 500));
                evidence.add(new SupportEvidence("TOOL", label, result.text(), citation));
                citations.add(citation);
            }
        }

        boolean grounded = !docs.isEmpty() || diagnosticsAvailable;
        return new AggregatedEvidence(evidence, dedup(citations), traces, invocations,
                diagnosticsAvailable, grounded);
    }

    /** True when a diagnostics tool returned actual persisted data. */
    static boolean hasDiagnosticFacts(SupportToolCallResult result) {
        String text = result.text() == null ? "" : result.text().strip();
        return switch (result.name()) {
            case "get_job_diagnostics" -> text.contains("\"available\":true")
                    || text.contains("\"available\" : true");
            case "get_job_status" -> !text.startsWith("(задач нет)");
            case "get_stage_runs" -> !text.startsWith("(checkpoints не найдены)");
            default -> false;
        };
    }

    private static List<SupportCitation> dedup(List<SupportCitation> citations) {
        Map<String, SupportCitation> unique = new LinkedHashMap<>();
        for (SupportCitation citation : citations) {
            unique.putIfAbsent(citation.kind() + ":" + citation.source(), citation);
        }
        return new ArrayList<>(unique.values());
    }

    private static String labelFor(String tool) {
        return switch (tool) {
            case "get_job_status" -> "Статус задачи";
            case "get_stage_runs" -> "Стадии обработки";
            case "get_job_diagnostics" -> "Диагностика задачи";
            case "get_model_health" -> "Доступность модели";
            case "get_ticket" -> "Обращение";
            case "list_ticket_messages" -> "История обращения";
            case "search_support_tickets" -> "Поиск обращений";
            default -> tool;
        };
    }

    private static String kindFor(String tool) {
        return switch (tool) {
            case "get_ticket", "list_ticket_messages", "search_support_tickets" -> "TICKET";
            case "get_model_health" -> "MODEL";
            default -> "JOB";
        };
    }

    static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        return stripped.length() <= 400 ? stripped : stripped.substring(0, 400) + "…";
    }

    static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
