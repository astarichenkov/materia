package com.lectureprocessor.pipeline;

import java.time.Instant;

/**
 * Live, in-memory progress snapshot exposed to the UI/API. Persisted stage
 * transitions remain the source of truth; this only adds download telemetry and
 * a human-readable message.
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
        Instant updatedAt) {

    public static JobProgress queued(String lectureId, int totalStages) {
        return new JobProgress(lectureId, JobState.QUEUED, null, 0, totalStages,
                "В очереди", null, null, null, 0.0, null, null, Instant.now());
    }

    public JobProgress withState(JobState newState) {
        return new JobProgress(lectureId, newState, currentStage, stageIndex, totalStages, message,
                downloadStatus, bytesDownloaded, bytesTotal, percent, speedBps, etaSeconds,
                Instant.now());
    }

    public JobProgress withStage(PipelineStage stage, int index, String newMessage) {
        return new JobProgress(lectureId, JobState.RUNNING, stage, index, totalStages, newMessage,
                downloadStatus, bytesDownloaded, bytesTotal, percent, speedBps, etaSeconds,
                Instant.now());
    }

    public JobProgress withMessage(String newMessage) {
        return new JobProgress(lectureId, state, currentStage, stageIndex, totalStages, newMessage,
                downloadStatus, bytesDownloaded, bytesTotal, percent, speedBps, etaSeconds,
                Instant.now());
    }

    public JobProgress withDownload(String status, Long downloaded, Long total, Double pct,
            Double speed, Long eta) {
        return new JobProgress(lectureId, state, currentStage, stageIndex, totalStages, message,
                status, downloaded, total, pct, speed, eta, Instant.now());
    }
}
