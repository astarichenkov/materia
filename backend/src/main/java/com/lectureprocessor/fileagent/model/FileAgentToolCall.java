package com.lectureprocessor.fileagent.model;

import java.time.Instant;

/** Persisted tool trace entry for one task. Arguments are bounded and redacted. */
public record FileAgentToolCall(
        Long id,
        String taskId,
        String toolName,
        String arguments,
        boolean ok,
        long durationMs,
        String summary,
        Instant createdAt) {
}
