package com.lectureprocessor.processing;

import java.util.List;

import com.lectureprocessor.pipeline.PipelineStage;

/**
 * Server-computed preview of what a re-run would do. Nothing is started by
 * computing a plan: every decision is derived from real checkpoints, artifacts
 * and the requested output profile.
 */
public record RerunPlan(
        String lectureId,
        ProcessingProfile requested,
        ProcessingProfile applied,
        boolean blocked,
        String blockedReason,
        List<StagePlan> stages,
        List<String> reusedArtifacts,
        List<String> producedArtifacts,
        boolean manualEditsPresent,
        String manualEditsWarning,
        List<String> warnings) {

    /** What will happen to one pipeline stage. */
    public enum Decision {
        /** Valid checkpoint: the stage is not executed. */
        REUSE("Используется готовый результат"),
        /** The stage will actually run. */
        RERUN("Будет выполнено заново"),
        /** Not required for the requested result. */
        SKIP("Не требуется"),
        /** Cannot run now (another job is active or inputs are missing). */
        BLOCKED("Заблокировано");

        private final String label;

        Decision(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record StagePlan(PipelineStage stage, Decision decision, String reason,
            List<String> artifacts) {

        public boolean reruns() {
            return decision == Decision.RERUN;
        }

        public String stageTitle() {
            return stage.title();
        }
    }

    public boolean needsRerun() {
        return stages.stream().anyMatch(StagePlan::reruns);
    }

    public List<StagePlan> rerunStages() {
        return stages.stream().filter(StagePlan::reruns).toList();
    }
}
