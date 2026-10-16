package com.lectureprocessor.assistant;

import java.util.List;

/**
 * Result of a developer-assistant question.
 *
 * @param answer    model answer grounded in documentation and project context
 * @param citations documentation sources retrieved for the question
 * @param toolCalls MCP tools invoked to enrich the context (git, jobs, ...)
 * @param grounded  false when no documentation/context was available at all
 */
public record AssistantAnswer(
        String answer,
        List<AssistantCitation> citations,
        List<AssistantToolCall> toolCalls,
        boolean grounded) {
}
