package com.lectureprocessor.pipeline;

import java.util.List;

/**
 * Outcome of one stage execution.
 */
public record StageResult(
        PipelineStage stage,
        StageState state,
        ArtifactManifest manifest,
        String error,
        long durationMs,
        List<String> warnings) {

    public static StageResult completed(PipelineStage stage, ArtifactManifest manifest, long durationMs) {
        return new StageResult(stage, StageState.COMPLETED, manifest, null, durationMs, List.of());
    }

    public static StageResult completed(PipelineStage stage, ArtifactManifest manifest, long durationMs,
            List<String> warnings) {
        return new StageResult(stage, StageState.COMPLETED, manifest, null, durationMs,
                warnings == null ? List.of() : List.copyOf(warnings));
    }

    public static StageResult failed(PipelineStage stage, String error, long durationMs) {
        return new StageResult(stage, StageState.FAILED, ArtifactManifest.empty(), error, durationMs,
                List.of());
    }

    public static StageResult cancelled(PipelineStage stage, long durationMs) {
        return new StageResult(stage, StageState.CANCELLED, ArtifactManifest.empty(),
                "Отменено пользователем", durationMs, List.of());
    }

    public boolean success() {
        return state == StageState.COMPLETED;
    }
}
