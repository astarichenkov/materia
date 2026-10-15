package com.lectureprocessor.pipeline;

import java.time.Instant;

/**
 * Persisted pipeline job row (lease-based concurrency control and recovery).
 */
public record PipelineJob(
        String lectureId,
        JobState state,
        PipelineStage currentStage,
        int attempts,
        String leaseOwner,
        Instant leaseUntil,
        String error,
        Instant createdAt,
        Instant updatedAt) {
}
