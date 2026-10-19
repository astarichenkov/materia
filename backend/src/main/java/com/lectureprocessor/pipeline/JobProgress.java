package com.lectureprocessor.pipeline;

import java.time.Instant;

/**
 * Live, in-memory progress snapshot exposed to the UI/API. Persisted stage
 * transitions remain the source of truth; this only adds download telemetry and
 * a human-readable message.
 *
 * <p>{@code run*} fields describe the <em>current regeneration run</em>, not the
 * whole lecture history. They are copied into {@link JobStatusView.RunView} so a
 * second run never inherits the previous 100%.</p>
 */
public record JobProgress(
        String lectureId,
        JobState state,
        PipelineStage currentStage,
        int stageIndex,
        int totalStages,
        String message,
        String downloadStatus,
        Long bytesDownloaded,
        Long bytesTotal,
        Double percent,
        Double speedBps,
        Long etaSeconds,
        Instant updatedAt,
        String runId,
        String runState,
        int runDone,
        int runTotal,
        int runPercent) {

    public static JobProgress queued(String lectureId, int totalStages, String runId) {
        return new JobProgress(lectureId, JobState.QUEUED, null, 0, totalStages,
                "В очереди", null, null, null, 0.0, null, null, Instant.now(),
                runId, JobState.QUEUED.name(), 0, 0, 0);
    }

    public JobProgress withState(JobState newState) {
        return new JobProgress(lectureId, newState, currentStage, stageIndex, totalStages, message,
                downloadStatus, bytesDownloaded, bytesTotal, percent, speedBps, etaSeconds,
                Instant.now(), runId, newState == null ? runState : newState.name(), runDone,
                runTotal, runPercent);
    }

    public JobProgress withStage(PipelineStage stage, int index, String newMessage) {
        return new JobProgress(lectureId, JobState.RUNNING, stage, index, totalStages, newMessage,
                downloadStatus, bytesDownloaded, bytesTotal, percent, speedBps, etaSeconds,
                Instant.now(), runId, JobState.RUNNING.name(), runDone, runTotal, runPercent);
    }

    public JobProgress withMessage(String newMessage) {
        return new JobProgress(lectureId, state, currentStage, stageIndex, totalStages, newMessage,
                downloadStatus, bytesDownloaded, bytesTotal, percent, speedBps, etaSeconds,
                Instant.now(), runId, runState, runDone, runTotal, runPercent);
    }

    public JobProgress withDownload(String status, Long downloaded, Long total, Double pct,
            Double speed, Long eta) {
        return new JobProgress(lectureId, state, currentStage, stageIndex, totalStages, message,
                status, downloaded, total, pct, speed, eta, Instant.now(), runId, runState,
                runDone, runTotal, runPercent);
    }

    /** Updates the current-run counters (planned stages and chunk progress). */
    public JobProgress withRun(String newRunId, JobState newRunState, int done, int total,
            String newMessage) {
        int pct = total <= 0 ? 0
                : (newRunState == JobState.COMPLETED || newRunState == JobState.PARTIAL ? 100
                        : (int) Math.round(Math.min(done, total) * 100.0 / total));
        return new JobProgress(lectureId, state, currentStage, stageIndex, totalStages, newMessage,
                downloadStatus, bytesDownloaded, bytesTotal, percent, speedBps, etaSeconds,
                Instant.now(), newRunId, newRunState == null ? null : newRunState.name(), done,
                total, pct);
    }
}
