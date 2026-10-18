package com.lectureprocessor.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.repository.DownloadStateRepository;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.repository.StageCheckpointRepository;

/**
 * Builds a {@link JobStatusView} from persisted state plus the in-memory live
 * progress snapshot. Percent is stage-based (never invented): only real download
 * progress adds a finer-grained percentage.
 *
 * <p>Cancellation/interruption consistency: a terminal job must never show an
 * active stage. The persisted job state is authoritative; when the job row is
 * stale (for example a historical {@code CANCELLED} job with a {@code RUNNING}
 * checkpoint, or a cancelled lecture whose job row was never finished), the
 * effective view resolves the contradiction without modifying the database.</p>
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

        JobState rawJobState = job == null ? null : job.state();
        JobState effectiveJobState = effectiveJobState(rawJobState, lecture.status());
        boolean terminal = effectiveJobState != null
                && (effectiveJobState.isTerminal() || effectiveJobState == JobState.INTERRUPTED);

        int completed = (int) checkpoints.stream()
                .filter(c -> c.state() == StageState.COMPLETED)
                .count();
        PipelineStage current = job == null ? null : job.currentStage();
        boolean running = !terminal && effectiveJobState == JobState.RUNNING && current != null;
        int stageIndex = current == null ? completed : current.ordinal() + 1;
        int progressPercent = allStages.length == 0 ? 0
                : (int) Math.round(completed * 100.0 / allStages.length);
        if (running) {
            progressPercent = (int) Math.round((current.ordinal() + 1) * 100.0 / allStages.length);
        }

        List<JobStatusView.StageView> stageViews = new ArrayList<>();
        for (PipelineStage stage : allStages) {
            StageCheckpoint cp = byStage.get(stage);
            String rawState = cp == null ? "PENDING" : cp.state().name();
            stageViews.add(new JobStatusView.StageView(stage.name(), stage.title(),
                    effectiveStageState(rawState, terminal, effectiveJobState), rawState,
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
                .filter(cp -> cp.state() == StageState.FAILED || cp.state() == StageState.INTERRUPTED)
                .forEach(cp -> warnings.add(cp.stage() + ": " + cp.error()));

        String message = message(lecture, job, effectiveJobState, terminal);

        return new JobStatusView(lectureId, statusName(effectiveJobState, lecture.status()),
                lecture.status().name(),
                effectiveJobState == null ? null : effectiveJobState.name(),
                rawJobState == null ? null : rawJobState.name(),
                current == null ? null : current.name(),
                current == null ? null : current.title(),
                stageIndex, allStages.length, progressPercent, message, download, stageViews, warnings,
                terminal);
    }

    /**
     * A terminal lecture/job wins over a stale non-terminal counterpart. The
     * database values are preserved; only the view is normalized.
     */
    static JobState effectiveJobState(JobState rawJobState, JobStatus lectureStatus) {
        if (rawJobState != null && (rawJobState.isTerminal() || rawJobState == JobState.INTERRUPTED)) {
            return rawJobState;
        }
        if (lectureStatus != null
                && (lectureStatus.isTerminal() || lectureStatus == JobStatus.INTERRUPTED)) {
            return switch (lectureStatus) {
                case COMPLETED -> JobState.COMPLETED;
                case PARTIAL -> JobState.PARTIAL;
                case FAILED -> JobState.FAILED;
                case CANCELLED -> JobState.CANCELLED;
                case INTERRUPTED -> JobState.INTERRUPTED;
                default -> rawJobState;
            };
        }
        return rawJobState;
    }

    /** Stages that are not completed must not look active once the job is terminal. */
    static String effectiveStageState(String rawState, boolean terminal, JobState effectiveJobState) {
        if (!terminal) {
            return rawState;
        }
        if ("RUNNING".equals(rawState)) {
            return effectiveJobState == JobState.CANCELLED ? "CANCELLED" : "INTERRUPTED";
        }
        if ("PENDING".equals(rawState)) {
            return "SKIPPED";
        }
        return rawState;
    }

    static String statusName(JobState effectiveJobState, JobStatus lectureStatus) {
        if (effectiveJobState == null) {
            return lectureStatus.name();
        }
        return switch (effectiveJobState) {
            case COMPLETED -> JobStatus.COMPLETED.name();
            case PARTIAL -> JobStatus.PARTIAL.name();
            case FAILED -> JobStatus.FAILED.name();
            case CANCELLED -> JobStatus.CANCELLED.name();
            case INTERRUPTED -> JobStatus.INTERRUPTED.name();
            // QUEUED/RUNNING keep the fine-grained lecture status (TRANSCRIBING, ...).
            case QUEUED, RUNNING -> lectureStatus.name();
        };
    }

    private String message(Lecture lecture, PipelineJob job, JobState effectiveJobState, boolean terminal) {
        if (terminal) {
            return switch (effectiveJobState) {
                case CANCELLED -> nonBlank(job == null ? null : job.error(), "Отменено пользователем");
                case FAILED -> nonBlank(lecture.errorMessage(),
                        job == null ? null : job.error(), "Обработка завершилась ошибкой");
                case INTERRUPTED -> nonBlank(lecture.errorMessage(),
                        job == null ? null : job.error(), "Обработка прервана");
                default -> "";
            };
        }
        Optional<JobProgress> live = progressRegistry.get(lecture.id());
        return live.map(JobProgress::message)
                .orElse(lecture.errorMessage() == null ? "" : lecture.errorMessage());
    }

    private static String nonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private static String nonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : fallback;
    }
}
