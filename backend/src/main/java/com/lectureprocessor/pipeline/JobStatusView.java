package com.lectureprocessor.pipeline;

import java.time.Instant;
import java.util.List;

/**
 * Composite job view for the UI/API: high-level status, progress, download
 * telemetry and per-stage states.
 */
public record JobStatusView(
        String lectureId,
        String status,
        String jobState,
        String currentStage,
        String currentStageTitle,
        int stageIndex,
        int totalStages,
        int progressPercent,
        String message,
        DownloadView download,
        List<StageView> stages,
        List<String> warnings) {

    public record DownloadView(
            String status,
            Long bytesDownloaded,
            Long bytesTotal,
            Double percent,
            Double speedBps,
            Long etaSeconds,
            String error) {
    }

    public record StageView(
            String stage,
            String title,
            String state,
            String startedAt,
            String finishedAt,
            String error) {
    }
}
