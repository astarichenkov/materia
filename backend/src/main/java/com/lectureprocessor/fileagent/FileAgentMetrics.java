package com.lectureprocessor.fileagent;

import java.util.Map;

/** Aggregate file-agent metrics shown on the dashboard. */
public record FileAgentMetrics(
        int totalTasks,
        int completedTasks,
        int awaitingApproval,
        int failedTasks,
        int conflicts,
        int rejectedTasks,
        long toolCalls,
        long toolFailures,
        long filesChanged,
        long rollbackAttempts,
        double approvalRate,
        Map<String, Integer> providerBreakdown) {
}
