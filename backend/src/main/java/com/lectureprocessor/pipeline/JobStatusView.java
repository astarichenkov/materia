package com.lectureprocessor.pipeline;

import java.util.List;

/**
 * Composite job view for the UI/API: high-level status, progress, download
 * telemetry and per-stage states.
 *
 * <p>{@code status}/{@code jobState} are the <em>effective</em> values: a
 * persisted job/lecture contradiction (for example {@code lecture=CANCELLED}
 * with a stale {@code stage=RUNNING}) is resolved for display, while the raw
 * values stay available in {@code rawStatus}/{@code rawJobState} and
 * {@link StageView#rawState()} for diagnostics. Historical rows are never
 * rewritten.</p>
 */
public record JobStatusView(
        String lectureId,
        String status,
        String rawStatus,
        String jobState,
        String rawJobState,
        String currentStage,
        String currentStageTitle,
        int stageIndex,
        int totalStages,
        int progressPercent,
        String message,
        DownloadView download,
        List<StageView> stages,
        List<String> warnings,
        boolean terminal) {

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
            String rawState,
            String startedAt,
            String finishedAt,
            String error) {
    }
}
