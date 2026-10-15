package com.lectureprocessor.pipeline;

/**
 * One independent, checkpointable stage of the pipeline. Implementations are
 * Spring beans discovered by {@link PipelineCoordinator}.
 */
public interface StageExecutor {

    PipelineStage id();

    /**
     * A stable fingerprint of everything that affects this stage's output. If it
     * changes, a previously completed checkpoint is invalidated and the stage
     * (and its dependents) rerun.
     */
    String inputFingerprint(JobContext context);

    StageResult execute(JobContext context);

    /**
     * Optional stages do not fail the whole job; their failure is recorded as a
     * warning and the pipeline continues.
     */
    default boolean optional() {
        return false;
    }

    /**
     * Whether this stage must run to produce the deliverable documents. Stages
     * that only feed RAG are non-essential.
     */
    default boolean requiredForExport() {
        return true;
    }
}
