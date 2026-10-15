package com.lectureprocessor.pipeline;

import java.time.Instant;

/**
 * Persisted per-stage checkpoint. Reused only if {@code state == COMPLETED},
 * the fingerprint matches and the artifact manifest is valid.
 */
public record StageCheckpoint(
        String lectureId,
        PipelineStage stage,
        StageState state,
        String fingerprint,
        int attempt,
        Instant startedAt,
        Instant finishedAt,
        String error,
        ArtifactManifest manifest) {
}
