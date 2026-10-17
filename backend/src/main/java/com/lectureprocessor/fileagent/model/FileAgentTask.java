package com.lectureprocessor.fileagent.model;

import java.time.Instant;
import java.util.List;

import com.lectureprocessor.fileagent.FileAgentTaskStatus;
import com.lectureprocessor.fileagent.FileWorkspace;

/** Persisted state and metrics of one autonomous file-agent task. */
public record FileAgentTask(
        String id,
        FileWorkspace workspace,
        String lectureId,
        String goal,
        FileAgentTaskStatus status,
        String plan,
        List<String> relevantFiles,
        String provider,
        String model,
        String error,
        String resultSummary,
        int filesSearched,
        int filesRead,
        int filesProposed,
        int filesChanged,
        int toolCalls,
        int toolFailures,
        long planningMs,
        long durationMs,
        Integer inputTokens,
        Integer outputTokens,
        Double estimatedCost,
        String costCurrency,
        int rollbackAttempts,
        String verificationStatus,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public FileAgentTask withStatus(FileAgentTaskStatus next, Instant now) {
        return new FileAgentTask(id, workspace, lectureId, goal, next, plan, relevantFiles, provider,
                model, error, resultSummary, filesSearched, filesRead, filesProposed, filesChanged,
                toolCalls, toolFailures, planningMs, durationMs, inputTokens, outputTokens,
                estimatedCost, costCurrency, rollbackAttempts, verificationStatus, createdAt,
                now, completedAt);
    }
}
