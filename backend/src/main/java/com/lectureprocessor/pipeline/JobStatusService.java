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
import com.lectureprocessor.repository.PipelineRunRepository;
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
    private final PipelineRunRepository runRepository;

    public JobStatusService(LectureRepository lectureRepository, PipelineJobRepository jobRepository,
            StageCheckpointRepository checkpointRepository,
            DownloadStateRepository downloadStateRepository, JobProgressRegistry progressRegistry,
            PipelineRunRepository runRepository) {
        this.lectureRepository = lectureRepository;
        this.jobRepository = jobRepository;
        this.checkpointRepository = checkpointRepository;
        this.downloadStateRepository = downloadStateRepository;
        this.progressRegistry = progressRegistry;
        this.runRepository = runRepository;
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
        } else if (terminal && (effectiveJobState == JobState.COMPLETED
                || effectiveJobState == JobState.PARTIAL)) {
            // A successful lecture is 100% even when optional stages (translation,
            // slides, RAG) have no checkpoint because they were not required.
            progressPercent = 100;
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
        JobStatusView.RunView run = buildRunView(lectureId, effectiveJobState, terminal);

        return new JobStatusView(lectureId, statusName(effectiveJobState, lecture.status()),
                lecture.status().name(),
                effectiveJobState == null ? null : effectiveJobState.name(),
                rawJobState == null ? null : rawJobState.name(),
                current == null ? null : current.name(),
                current == null ? null : current.title(),
                stageIndex, allStages.length, progressPercent, message, download, stageViews, warnings,
                terminal, run);
    }

    /**
     * Builds the run-scoped view. Only the latest run is shown; its percentage is
     * computed from its own planned stages so a reused pipeline never reads as a
     * completed new generation.
     */
    private JobStatusView.RunView buildRunView(String lectureId,
            JobState effectiveJobState, boolean terminal) {
        Optional<PipelineRun> latest = runRepository.findLatest(lectureId);
        if (latest.isEmpty()) {
            return null;
        }
        PipelineRun run = latest.get();
        Map<PipelineStage, StageCheckpoint> checkpointsByStage = checkpointRepository
                .findByLecture(lectureId).stream()
                .collect(Collectors.toMap(StageCheckpoint::stage, Function.identity(), (a, b) -> a));
        boolean runTerminal = run.terminal();
        List<JobStatusView.RunStageView> runStages = new ArrayList<>();
        for (PipelineStage stage : run.plannedStages()) {
            StageCheckpoint cp = checkpointsByStage.get(stage);
            String state;
            String detail = null;
            boolean completedInThisRun = cp != null && cp.state() == StageState.COMPLETED
                    && cp.finishedAt() != null && !cp.finishedAt().isBefore(run.createdAt());
            if (run.currentStage() == stage && !runTerminal) {
                state = "RUNNING";
            } else if (completedInThisRun) {
                state = "COMPLETED";
            } else if (runTerminal) {
                state = run.state() == JobState.CANCELLED ? "CANCELLED" : "SKIPPED";
            } else {
                state = "PENDING";
            }
            if (run.currentStage() == stage && run.stageTotal() > 0) {
                detail = "обработано " + run.stageDone() + " из " + run.stageTotal() + " фрагментов";
            }
            runStages.add(new JobStatusView.RunStageView(stage.name(), stage.title(), state, detail));
        }
        int percent = run.percent();
        if (run.currentStage() != null && !runTerminal && run.stageTotal() > 0) {
            // Fine-grained chunk progress inside the current stage, blended with
            // completed stages; never reaches 100 before the run succeeds.
            int planned = Math.max(run.plannedStages().size(), 1);
            double stageFraction = Math.min(0.99, (double) run.stageDone() / run.stageTotal());
            int completedStages = (int) runStages.stream()
                    .filter(s -> "COMPLETED".equals(s.state())).count();
            percent = Math.min(99, (int) Math.round((completedStages + stageFraction) * 100.0 / planned));
        }
        String stateLabel = switch (run.state()) {
            case QUEUED -> "Ожидает";
            case RUNNING -> "Выполняется";
            case COMPLETED -> "Готово";
            case PARTIAL -> "Готово с предупреждениями";
            case FAILED -> "Ошибка";
            case CANCELLED -> "Отменён";
            case INTERRUPTED -> "Прерван";
        };
        String message = run.message();
        if (run.state() == JobState.COMPLETED && (message == null || message.isBlank())) {
            message = "Готово: новый результат создан";
        }
        if (runTerminal && !run.succeeded() && effectiveJobState == JobState.COMPLETED) {
            // The run is the authoritative terminal state for its own progress.
            stateLabel = "Завершён";
        }
        String stageProgress = run.stageTotal() > 0
                ? run.stageDone() + " из " + run.stageTotal() + " фрагментов" : null;
        return new JobStatusView.RunView(run.id(), run.state() == null ? null : run.state().name(),
                stateLabel, message,
                run.currentStage() == null ? null : run.currentStage().name(),
                run.currentStage() == null ? null : run.currentStage().title(),
                percent, run.stageDone(), run.plannedStages().size(),
                run.createdAt() == null ? null : run.createdAt().toString(),
                run.finishedAt() == null ? null : run.finishedAt().toString(),
                stageProgress, runStages);
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
                case COMPLETED -> "Готово: документы созданы";
                case PARTIAL -> "Готово с предупреждениями: документы созданы";
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
