package com.lectureprocessor.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.pipeline.JobStatusView;
import com.lectureprocessor.pipeline.JobStatusService;
import com.lectureprocessor.pipeline.PipelineJob;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;

/**
 * Read-only jobs view built on the existing pipeline_job rows and
 * {@link JobStatusService}. Actions are posted to the existing lecture
 * endpoints; no new mutation or persistence logic is added.
 */
@Controller
public class JobsWebController {

    private static final int LIMIT = 50;

    private final PipelineJobRepository jobs;
    private final LectureRepository lectures;
    private final JobStatusService status;

    public JobsWebController(PipelineJobRepository jobs, LectureRepository lectures,
            JobStatusService status) {
        this.jobs = jobs;
        this.lectures = lectures;
        this.status = status;
    }

    @GetMapping("/jobs")
    public String jobs(Model model) {
        List<Row> rows = new ArrayList<>();
        for (PipelineJob job : jobs.findAll()) {
            if (rows.size() >= LIMIT) {
                break;
            }
            Lecture lecture = lectures.findById(job.lectureId()).orElse(null);
            if (lecture == null) {
                continue;
            }
            JobStatusView view = null;
            try {
                view = status.view(job.lectureId());
            } catch (RuntimeException ignored) {
                // A missing live snapshot must not break the whole jobs page.
            }
            rows.add(new Row(job, lecture, view));
        }
        model.addAttribute("jobs", rows);
        return "jobs";
    }

    public record Row(PipelineJob job, Lecture lecture, JobStatusView view) {
    }
}
