package com.lectureprocessor.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.process.CancellationToken;
import com.lectureprocessor.process.ProcessCancelledException;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.repository.StageCheckpointRepository;
import com.lectureprocessor.repository.TimingRepository;
import com.lectureprocessor.storage.FileStorageService;

/**
 * Runs the resumable pipeline for one lecture.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>atomic DB lease claim (no duplicate runs, survives restarts);</li>
 *   <li>checkpoint reuse/invalidation so a failed LLM never re-runs Whisper;</li>
 *   <li>per-stage status, progress and error persistence;</li>
 *   <li>cancellation of external work via a {@link CancellationToken}.</li>
 * </ul>
 */
@Service
public class PipelineCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PipelineCoordinator.class);
    private static final Duration LEASE_DURATION = Duration.ofHours(1);

    private final List<StageExecutor> stages;
    private final PipelineJobRepository jobRepository;
    private final StageCheckpointRepository checkpointRepository;
    private final LectureRepository lectureRepository;
    private final PipelineDataLoader loader;
    private final JobProgressRegistry progressRegistry;
    private final TimingRepository timings;
    private final FileStorageService storage;

    private final Map<String, CancellationToken> tokens = new ConcurrentHashMap<>();
    private final String owner = "app-" + UUID.randomUUID();

    public PipelineCoordinator(List<StageExecutor> stages, PipelineJobRepository jobRepository,
            StageCheckpointRepository checkpointRepository, LectureRepository lectureRepository,
            PipelineDataLoader loader, JobProgressRegistry progressRegistry, TimingRepository timings,
            FileStorageService storage) {
        this.stages = stages.stream()
                .sorted(Comparator.comparingInt(s -> s.id().ordinal()))
                .toList();
        this.jobRepository = jobRepository;
        this.checkpointRepository = checkpointRepository;
        this.lectureRepository = lectureRepository;
        this.loader = loader;
        this.progressRegistry = progressRegistry;
        this.timings = timings;
        this.storage = storage;
    }

    public boolean isRunning(String lectureId) {
        return tokens.containsKey(lectureId);
    }

    public boolean cancel(String lectureId) {
        CancellationToken token = tokens.get(lectureId);
        if (token == null) {
            return false;
        }
        token.cancel();
        return true;
    }

    /**
     * Executes all stages, reusing valid checkpoints. Never throws for expected
     * stage failures: the job/lecture states are persisted instead.
     */
    public void run(String lectureId) {
        CancellationToken token = new CancellationToken();
        CancellationToken previous = tokens.putIfAbsent(lectureId, token);
        if (previous != null) {
            log.debug("Lecture {} is already running in this process", lectureId);
            return;
        }
        progressRegistry.set(JobProgress.queued(lectureId, stages.size()));
        if (!jobRepository.claim(lectureId, owner, Instant.now().plus(LEASE_DURATION))) {
            log.info("Lecture {} is already leased by another run; skipping", lectureId);
            tokens.remove(lectureId);
            progressRegistry.remove(lectureId);
            return;
        }

        long totalStart = System.nanoTime();
        JobContext context = new JobContext(lectureId, token);
        context.setLecture(loader.lecture(lectureId));
        try {
            int index = 0;
            for (StageExecutor stage : stages) {
                token.throwIfCancelled();
                ensureContext(context);
                jobRepository.renewLease(lectureId, owner, Instant.now().plus(LEASE_DURATION));
                index++;

                String fingerprint = stage.inputFingerprint(context);
                if (isCheckpointValid(lectureId, stage, fingerprint)) {
                    log.info("Lecture {} stage {} reused from checkpoint", lectureId, stage.id());
                    updateProgress(lectureId, stage, index, "Пропущено (уже выполнено)");
                    continue;
                }
                checkpointRepository.invalidateFrom(lectureId, stage.id());
                checkpointRepository.start(lectureId, stage.id(), fingerprint, attemptNumber(lectureId));
                updateStatus(context, statusFor(stage.id()));
                jobRepository.updateProgress(lectureId, owner, stage.id());
                updateProgress(lectureId, stage, index, stage.id().title());
                log.info("Lecture {} -> stage {}", lectureId, stage.id());

                StageResult result;
                try {
                    result = stage.execute(context);
                } catch (ProcessCancelledException e) {
                    throw e;
                } catch (RuntimeException e) {
                    checkpointRepository.fail(lectureId, stage.id(), e.getMessage());
                    if (stage.optional()) {
                        log.warn("Optional stage {} failed for lecture {}: {}", stage.id(), lectureId,
                                e.getMessage());
                        context.addWarning(stage.id() + ": " + e.getMessage());
                        context.setPartialSuccess(true);
                        continue;
                    }
                    fail(context, lectureId, e.getMessage());
                    return;
                }

                if (result.state() == StageState.COMPLETED) {
                    checkpointRepository.complete(lectureId, stage.id(), result.manifest());
                    result.warnings().forEach(context::addWarning);
                    continue;
                }
                if (result.state() == StageState.CANCELLED) {
                    throw new ProcessCancelledException(result.error());
                }
                checkpointRepository.fail(lectureId, stage.id(), result.error());
                if (stage.optional()) {
                    context.addWarning(stage.id() + ": " + result.error());
                    context.setPartialSuccess(true);
                    continue;
                }
                fail(context, lectureId, result.error());
                return;
            }

            JobState finalState = context.partialSuccess() ? JobState.PARTIAL : JobState.COMPLETED;
            JobStatus finalStatus = context.partialSuccess() ? JobStatus.PARTIAL : JobStatus.COMPLETED;
            updateStatus(context, finalStatus);
            jobRepository.finish(lectureId, finalState, null);
            timings.record(lectureId, TimingRepository.TOTAL, elapsedMs(totalStart));
            progressRegistry.update(lectureId, p -> p == null ? null : p.withState(finalState));
            log.info("Lecture {} finished with state {}", lectureId, finalState);
        } catch (ProcessCancelledException e) {
            log.info("Lecture {} cancelled", lectureId);
            updateStatus(context, JobStatus.CANCELLED);
            jobRepository.finish(lectureId, JobState.CANCELLED, "Отменено пользователем");
            progressRegistry.update(lectureId, p -> p == null ? null : p.withState(JobState.CANCELLED));
        } catch (RuntimeException e) {
            log.error("Lecture {} pipeline failed: {}", lectureId, e.getMessage(), e);
            fail(context, lectureId, e.getMessage());
        } finally {
            tokens.remove(lectureId);
        }
    }

    /** Rebuilds source/artifact/lecture in the context after a restart or skip. */
    private void ensureContext(JobContext context) {
        if (context.lecture() == null) {
            context.setLecture(loader.lecture(context.lectureId()));
        }
        if (context.source() == null) {
            context.setSource(loader.source(context.lectureId()));
        }
        if (context.artifact() == null) {
            context.setArtifact(loader.artifact(context.lectureId()));
        }
    }

    private boolean isCheckpointValid(String lectureId, StageExecutor stage, String fingerprint) {
        Optional<StageCheckpoint> checkpoint = checkpointRepository.find(lectureId, stage.id());
        if (checkpoint.isEmpty()) {
            return false;
        }
        StageCheckpoint cp = checkpoint.get();
        if (cp.state() != StageState.COMPLETED) {
            return false;
        }
        if (fingerprint == null || !fingerprint.equals(cp.fingerprint())) {
            log.info("Lecture {} stage {} fingerprint changed; invalidating", lectureId, stage.id());
            return false;
        }
        return cp.manifest().isValid(storage, lectureId);
    }

    private int attemptNumber(String lectureId) {
        return jobRepository.find(lectureId).map(PipelineJob::attempts).orElse(1);
    }

    private void updateStatus(JobContext context, JobStatus status) {
        Lecture lecture = context.lecture() != null ? context.lecture() : loader.lecture(context.lectureId());
        Lecture updated = lecture.withStatus(status);
        lectureRepository.save(updated);
        context.setLecture(updated);
    }

    private void fail(JobContext context, String lectureId, String message) {
        String safeMessage = message == null ? "Неизвестная ошибка обработки" : message;
        updateStatus(context, JobStatus.FAILED);
        lectureRepository.save(loader.lecture(lectureId).withError(safeMessage));
        jobRepository.finish(lectureId, JobState.FAILED, safeMessage);
        progressRegistry.update(lectureId, p -> p == null ? null
                : p.withState(JobState.FAILED).withMessage(safeMessage));
    }

    private void updateProgress(String lectureId, StageExecutor stage, int index, String message) {
        progressRegistry.update(lectureId, p -> p == null ? null : p.withStage(stage.id(), index, message));
    }

    static JobStatus statusFor(PipelineStage stage) {
        return switch (stage) {
            case SOURCE_RESOLUTION -> JobStatus.UPLOADED;
            case DOWNLOADING -> JobStatus.DOWNLOADING;
            case MEDIA_VALIDATION -> JobStatus.VALIDATING;
            case AUDIO_EXTRACTION -> JobStatus.EXTRACTING_AUDIO;
            case TRANSCRIPTION -> JobStatus.TRANSCRIBING;
            case TEXT_PROCESSING, RAG_INDEXING -> JobStatus.PROCESSING_TEXT;
            case SLIDE_EXTRACTION -> JobStatus.EXTRACTING_SLIDES;
            case EXPORT -> JobStatus.EXPORTING;
        };
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
