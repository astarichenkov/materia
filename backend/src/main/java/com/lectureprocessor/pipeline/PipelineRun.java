package com.lectureprocessor.pipeline;

import java.time.Instant;
import java.util.List;

/**
 * One concrete pipeline run. The checkpoints tell us what has ever been produced
 * for a lecture; this record tells us what the <em>current run</em> is doing.
 * Without it a regeneration after a completed pipeline would keep showing the
 * previous 100% until the first stage starts.
 */
public record PipelineRun(
        String id,
        String lectureId,
        JobState state,
        List<PipelineStage> plannedStages,
        PipelineStage currentStage,
        String message,
        String error,
        int stageDone,
        int stageTotal,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public PipelineRun {
        plannedStages = plannedStages == null ? List.of() : List.copyOf(plannedStages);
    }

    public boolean terminal() {
        return state != null && (state.isTerminal() || state == JobState.INTERRUPTED);
    }

    public boolean succeeded() {
        return state == JobState.COMPLETED || state == JobState.PARTIAL;
    }

    public boolean planned(PipelineStage stage) {
        return plannedStages.contains(stage);
    }

    /** Progress of this run only; 100 is reached exclusively on success. */
    public int percent() {
        if (plannedStages.isEmpty()) {
            return 0;
        }
        if (state == JobState.COMPLETED || state == JobState.PARTIAL) {
            return 100;
        }
        int done = Math.min(stageDone, plannedStages.size());
        return (int) Math.round(done * 100.0 / plannedStages.size());
    }
}
