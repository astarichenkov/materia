package com.lectureprocessor.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.assistant.DocSearchResult;
import com.lectureprocessor.assistant.DocumentationRetrievalService;

/**
 * Builds the bounded evidence context for one question: routes the intent,
 * executes only eligible read-only tools through {@link SupportToolExecutor},
 * retrieves documentation and aggregates everything into {@link SupportContext}.
 *
 * <p>Tool failures never break the surface — they are recorded as a trace and
 * degrade {@code grounded}/{@code diagnosticsAvailable} so the answer can be
 * honest about missing data.</p>
 */
@Service
public class SupportContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(SupportContextBuilder.class);

    private final SupportIntentRouter router;
    private final SupportToolExecutor toolExecutor;
    private final DocumentationRetrievalService retrieval;
    private final SupportEvidenceAggregator aggregator;
    private final SupportProperties properties;

    public SupportContextBuilder(SupportIntentRouter router, SupportToolExecutor toolExecutor,
            DocumentationRetrievalService retrieval, SupportEvidenceAggregator aggregator,
            SupportProperties properties) {
        this.router = router;
        this.toolExecutor = toolExecutor;
        this.retrieval = retrieval;
        this.aggregator = aggregator;
        this.properties = properties;
    }

    public SupportContext build(SupportTicket ticket, String lectureId, String question,
            SupportPrincipal principal) {
        return build(ticket, lectureId, question, principal, ticket != null);
    }

    public SupportContext build(SupportTicket ticket, String lectureId, String question,
            SupportPrincipal principal, boolean explicitTicket) {
        SupportRoute route = router.route(question, explicitTicket, lectureId != null);
        List<SupportToolCallResult> results = executeTools(route, ticket, lectureId, principal);
        List<DocSearchResult> docs = retrieveDocs(question);
        SupportEvidenceAggregator.AggregatedEvidence aggregated =
                aggregator.aggregate(docs, results, ticket, null);
        return new SupportContext(ticket, lectureId, route, aggregated.evidence(),
                aggregated.citations(), aggregated.traces(), aggregated.invocations(),
                aggregated.diagnosticsAvailable(), aggregated.grounded());
    }

    private List<SupportToolCallResult> executeTools(SupportRoute route, SupportTicket ticket,
            String lectureId, SupportPrincipal principal) {
        List<SupportToolCallResult> results = new ArrayList<>();
        int max = Math.max(0, properties.maxToolCalls());
        for (String tool : route.tools()) {
            if (results.size() >= max) {
                break;
            }
            Map<String, Object> arguments = argumentsFor(tool, ticket, lectureId);
            if (arguments == null) {
                continue;
            }
            SupportToolCallResult result = toolExecutor.execute(tool, arguments, principal);
            results.add(result);
        }
        return results;
    }

    /** Returns null when the tool cannot run for the available context. */
    private Map<String, Object> argumentsFor(String tool, SupportTicket ticket, String lectureId) {
        return switch (tool) {
            case "get_ticket", "list_ticket_messages" ->
                    ticket == null || ticket.id() == null ? null : Map.of("ticket_id", ticket.id());
            case "get_job_status", "get_stage_runs", "get_job_diagnostics" ->
                    lectureId == null || lectureId.isBlank() ? null : Map.of("lecture_id", lectureId);
            default -> Map.of();
        };
    }

    private List<DocSearchResult> retrieveDocs(String question) {
        try {
            return retrieval.search(question, properties.retrievalTopK(), properties.minSimilarity());
        } catch (RuntimeException e) {
            log.debug("Documentation retrieval unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    SupportRoute routeFor(String question, boolean hasTicket, boolean hasLecture) {
        return router.route(question, hasTicket, hasLecture);
    }
}
