package com.lectureprocessor.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.repository.DownloadStateRepository;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.repository.StageCheckpointRepository;

/**
 * Builds a {@link JobStatusView} from persisted state plus the in-memory live
 * progress snapshot. Percent is stage-based (never invented): only real download
 * progress adds a finer-grained percentage.
 */
@Service
public class JobStatusService {

    private final LectureRepository lectureRepository;
    private final PipelineJobRepository jobRepository;
    private final StageCheckpointRepository checkpointRepository;
    private final DownloadStateRepository downloadStateRepository;
    private final JobProgressRegistry progressRegistry;

    public JobStatusService(LectureRepository lectureRepository, PipelineJobRepository jobRepository,
            StageCheckpointRepository checkpointRepository,
            DownloadStateRepository downloadStateRepository, JobProgressRegistry progressRegistry) {
        this.lectureRepository = lectureRepository;
        this.jobRepository = jobRepository;
        this.checkpointRepository = checkpointRepository;
        this.downloadStateRepository = downloadStateRepository;
        this.progressRegistry = progressRegistry;
    }

    public JobStatusView view(String lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId).orElseThrow(
                () -> new com.lectureprocessor.lecture.LectureNotFoundException(lectureId));
        PipelineJob job = jobRepository.find(lectureId).orElse(null);
        List<StageCheckpoint> checkpoints = checkpointRepository.findByLecture(lectureId);
        Map<PipelineStage, StageCheckpoint> byStage = checkpoints.stream()
                .collect(Collectors.toMap(StageCheckpoint::stage, Function.identity(), (a, b) -> a));
        PipelineStage[] allStages = PipelineStage.values();

        int completed = (int) checkpoints.stream()
                .filter(c -> c.state() == StageState.COMPLETED)
                .count();
        PipelineStage current = job == null ? null : job.currentStage();
        boolean running = job != null && job.state() == JobState.RUNNING;
        int stageIndex = current == null ? completed : current.ordinal() + 1;
        int progressPercent = allStages.length == 0 ? 0
                : (int) Math.round(completed * 100.0 / allStages.length);
        if (running && current != null) {
            progressPercent = (int) Math.round((current.ordinal() + 1) * 100.0 / allStages.length);
        }

        List<JobStatusView.StageView> stageViews = new ArrayList<>();
        for (PipelineStage stage : allStages) {
            StageCheckpoint cp = byStage.get(stage);
            stageViews.add(new JobStatusView.StageView(stage.name(), stage.title(),
                    cp == null ? "PENDING" : cp.state().name(),
                    cp == null || cp.startedAt() == null ? null : cp.startedAt().toString(),
                    cp == null || cp.finishedAt() == null ? null : cp.finishedAt().toString(),
                    cp == null ? null : cp.error()));
        }

        JobStatusView.DownloadView download = downloadStateRepository.find(lectureId)
                .map(d -> new JobStatusView.DownloadView(d.status(), d.bytesDownloaded(),
                        d.bytesTotal(), d.percent(), d.speedBps(), d.etaSeconds(), d.error()))
                .orElse(null);

        List<String> warnings = new ArrayList<>();
        checkpoints.stream()
                .filter(cp -> cp.state() == StageState.FAILED || cp.state() == StageState.CANCELLED)
                .forEach(cp -> warnings.add(cp.stage() + ": " + cp.error()));

        Optional<JobProgress> live = progressRegistry.get(lectureId);
        String message = live.map(JobProgress::message)
                .orElse(lecture.errorMessage() == null ? "" : lecture.errorMessage());
        if (live.isPresent() && live.get().percent() != null && download == null) {
            // keep stage-based percent; live download percent lives in `download`
        }

        return new JobStatusView(lectureId, lecture.status().name(),
                job == null ? null : job.state().name(),
                current == null ? null : current.name(),
                current == null ? null : current.title(),
                stageIndex, allStages.length, progressPercent, message, download, stageViews, warnings);
    }
}
