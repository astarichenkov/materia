package com.lectureprocessor.support;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Day 33 support assistant.
 *
 * <p>Kept separate from {@code AppProperties} so existing tests and components
 * that construct {@code AppProperties} directly are not forced to change.</p>
 *
 * @param enabled             master switch for the support surface
 * @param installScope        scope stamped on every ticket of this installation
 * @param isolation           {@code install} (any local session) or {@code session}
 *                            (owner cookie must match) — documented local trust model
 * @param assistantPrompt     versioned prompt template name for the answer
 * @param allowedTools        explicit read-only MCP tool allow-list
 * @param maxToolCalls        hard cap on tool invocations per answer
 * @param maxToolResultChars  per-result character cap (secrets redacted, then cut)
 * @param maxQuestionChars    user question length cap
 * @param maxHistoryMessages  ticket messages included in the LLM context
 * @param maxContextChars     total evidence size sent to the LLM
 * @param maxLlmCalls         hard cap on LLM calls per answer
 * @param retrievalTopK       documentation snippets retrieved per question
 * @param minSimilarity       minimum cosine similarity for a docs snippet
 * @param timeout             hard timeout for a single answer
 */
@ConfigurationProperties(prefix = "app.support")
public record SupportProperties(
        boolean enabled,
        String installScope,
        String isolation,
        String assistantPrompt,
        List<String> allowedTools,
        int maxToolCalls,
        int maxToolResultChars,
        int maxQuestionChars,
        int maxHistoryMessages,
        int maxContextChars,
        int maxLlmCalls,
        int retrievalTopK,
        double minSimilarity,
        Duration timeout) {

    /** Safe defaults used when the record is constructed manually (tests). */
    public static SupportProperties defaults(String installScope) {
        return new SupportProperties(true, installScope, "install", "support-assistant-v1",
                List.of("get_job_status", "get_stage_runs", "get_job_diagnostics", "get_model_health",
                        "get_ticket", "list_ticket_messages", "search_support_tickets", "search_help_docs"),
                8, 4000, 4000, 10, 16000, 2, 6, 0.15, Duration.ofMinutes(2));
    }

    public boolean sessionIsolation() {
        return "session".equalsIgnoreCase(isolation);
    }
}
