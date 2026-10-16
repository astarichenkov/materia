package com.lectureprocessor.support;

import java.time.Instant;

/** Persisted record of one MCP tool invocation made for a support answer. */
public record SupportToolCall(
        Long id,
        Long messageId,
        Long ticketId,
        String toolName,
        boolean ok,
        Long durationMs,
        String summary,
        Instant createdAt) {
}
