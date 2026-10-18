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
    /**
     * Cancellation requests that arrived before the worker registered its token
     * (submit → executor hand-off). Consumed at the start of {@link #run(String)}.
     */
    private final java.util.Set<String> pendingCancellations = ConcurrentHashMap.newKeySet();
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
        if (token != null) {
            token.cancel();
            return true;
        }
        Optional<PipelineJob> stored = jobRepository.find(lectureId);
        if (stored.isEmpty()) {
            return false;
        }
        PipelineJob job = stored.get();
        if (job.state() != JobState.QUEUED && job.state() != JobState.RUNNING) {
            // Already finished, cancelled or interrupted: nothing to stop.
            return false;
        }
        if (job.state() == JobState.QUEUED) {
            // The worker may not have registered its token yet (submit → executor
            // hand-off); remember the request and consume it at the start of run().
            pendingCancellations.add(lectureId);
            return true;
        }
        if (job.leaseUntil() == null || job.leaseUntil().isBefore(Instant.now())) {
            return cancelPersisted(lectureId);
        }
        // A live lease without an in-process token cannot be cancelled safely.
        return false;
    }

    /**
     * Cancels a job whose worker is gone (expired lease). The persisted job, the
     * active checkpoint and the lecture are all moved to a terminal state so the
     * UI never shows a cancelled job with a running stage.
     */
    private boolean cancelPersisted(String lectureId) {
        int updated = jobRepository.cancelStale(lectureId, Instant.now(), "Отменено пользователем");
        if (updated == 0) {
            pendingCancellations.remove(lectureId);
            return false;
        }
        checkpointRepository.cancelRunning(lectureId);
        lectureRepository.findById(lectureId).ifPresent(lecture ->
                lectureRepository.save(lecture.withStatus(JobStatus.CANCELLED)));
        progressRegistry.remove(lectureId);
        pendingCancellations.remove(lectureId);
        log.info("Lecture {} cancelled without a live worker (stale lease)", lectureId);
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
        com.lectureprocessor.process.CancellationContext.set(token);
        if (!jobRepository.claim(lectureId, owner, Instant.now().plus(LEASE_DURATION))) {
            log.info("Lecture {} is already leased by another run; skipping", lectureId);
            tokens.remove(lectureId);
            pendingCancellations.remove(lectureId);
            progressRegistry.remove(lectureId);
            com.lectureprocessor.process.CancellationContext.clear();
            return;
        }

        long totalStart = System.nanoTime();
        JobContext context = new JobContext(lectureId, token);
        context.setLecture(loader.lecture(lectureId));
        try {
            // A cancel may have raced with the executor hand-off.
            if (pendingCancellations.remove(lectureId)) {
                checkpointRepository.cancelRunning(lectureId);
                updateStatus(context, JobStatus.CANCELLED);
                jobRepository.finish(lectureId, JobState.CANCELLED, "Отменено пользователем");
                progressRegistry.update(lectureId, p -> p == null ? null
                        : p.withState(JobState.CANCELLED).withMessage("Отменено пользователем"));
                log.info("Lecture {} cancelled before the first stage", lectureId);
                return;
            }
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

            // Cancellation wins even when the last stage just finished: a late
            // worker must never turn a cancelled request into COMPLETED.
            token.throwIfCancelled();
            JobState finalState = context.partialSuccess() ? JobState.PARTIAL : JobState.COMPLETED;
            JobStatus finalStatus = context.partialSuccess() ? JobStatus.PARTIAL : JobStatus.COMPLETED;
            updateStatus(context, finalStatus);
            jobRepository.finish(lectureId, finalState, null);
            timings.record(lectureId, TimingRepository.TOTAL, elapsedMs(totalStart));
            progressRegistry.update(lectureId, p -> p == null ? null : p.withState(finalState));
            log.info("Lecture {} finished with state {}", lectureId, finalState);
        } catch (ProcessCancelledException e) {
            log.info("Lecture {} cancelled", lectureId);
            // A cancelled job must not keep an active-looking stage checkpoint.
            checkpointRepository.cancelRunning(lectureId);
            updateStatus(context, JobStatus.CANCELLED);
            jobRepository.finish(lectureId, JobState.CANCELLED, "Отменено пользователем");
            progressRegistry.update(lectureId, p -> p == null ? null
                    : p.withState(JobState.CANCELLED).withMessage("Отменено пользователем"));
        } catch (RuntimeException e) {
            log.error("Lecture {} pipeline failed: {}", lectureId, e.getMessage(), e);
            fail(context, lectureId, e.getMessage());
        } finally {
            tokens.remove(lectureId);
            pendingCancellations.remove(lectureId);
            com.lectureprocessor.process.CancellationContext.clear();
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
            case TRANSLATION -> JobStatus.TRANSLATING;
            case TEXT_PROCESSING, RAG_INDEXING -> JobStatus.PROCESSING_TEXT;
            case SLIDE_EXTRACTION -> JobStatus.EXTRACTING_SLIDES;
            case EXPORT -> JobStatus.EXPORTING;
        };
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
