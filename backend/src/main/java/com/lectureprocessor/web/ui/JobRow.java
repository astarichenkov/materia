package com.lectureprocessor.web.ui;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.pipeline.JobStatusView;
import com.lectureprocessor.pipeline.PipelineJob;

/**
 * Read-only presentation row shared by the jobs screen and the dashboard.
 * Effective status/terminality always come from {@link JobStatusView}, never
 * from the raw persisted job state alone.
 */
public record JobRow(PipelineJob job, Lecture lecture, JobStatusView view) {

    public String status() {
        return view != null ? view.status() : lecture.status().name();
    }

    public boolean terminal() {
        return view != null ? view.terminal() : lecture.status().isTerminal();
    }

    public String title() {
        return lecture.originalFilename() == null || lecture.originalFilename().isBlank()
                ? lecture.id()
                : lecture.originalFilename();
    }

    public String stageTitle() {
        return view == null || view.currentStageTitle() == null ? "—" : view.currentStageTitle();
    }

    public int progressPercent() {
        return view == null ? 0 : view.progressPercent();
    }
}
