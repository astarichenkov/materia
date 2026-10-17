package com.lectureprocessor.fileagent.model;

import java.time.Instant;

/** One observable phase of a file-agent task. */
public record FileAgentExecution(
        Long id,
        String taskId,
        String phase,
        String status,
        String detail,
        Instant startedAt,
        Instant finishedAt,
        long durationMs) {
}
