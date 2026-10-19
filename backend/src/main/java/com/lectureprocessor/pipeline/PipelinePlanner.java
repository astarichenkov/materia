package com.lectureprocessor.pipeline;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.processing.ProcessingProfile;
import com.lectureprocessor.repository.StageCheckpointRepository;
import com.lectureprocessor.storage.FileStorageService;

/**
 * Single source of truth for "will this stage actually run?".
 *
 * <p>Both the re-run preview and the coordinator call this class, so a plan that
 * says {@code REUSE} cannot silently turn into an execution of that stage. The
 * decision is derived from the stage's own {@link StageExecutor#inputFingerprint}
 * plus the persisted checkpoint manifest — exactly the same rules the executor
 * applies.</p>
 */
@Service
public class PipelinePlanner {

    private static final Logger log = LoggerFactory.getLogger(PipelinePlanner.class);

    /** Why a stage is or is not covered by a valid checkpoint. */
    public enum Reason {
        /** Valid checkpoint with matching fingerprint and existing artifacts. */
        VALID,
        /** No checkpoint has ever been written. */
        NO_CHECKPOINT,
        /** The checkpoint exists but is not COMPLETED. */
        NOT_COMPLETED,
        /** The stage input changed since the checkpoint was written. */
        FINGERPRINT_CHANGED,
        /** A required artifact of the checkpoint is missing or empty. */
        ARTIFACT_MISSING,
        /** The stage is disabled for this lecture (translation off, slides off). */
        NOT_REQUIRED
    }

    public record Evaluation(PipelineStage stage, String fingerprint, boolean reusable,
            boolean required, Reason reason) {
    }

    private final List<StageExecutor> stages;
    private final StageCheckpointRepository checkpoints;
    private final PipelineDataLoader loader;
    private final FileStorageService storage;

    public PipelinePlanner(List<StageExecutor> stages, StageCheckpointRepository checkpoints,
            PipelineDataLoader loader, FileStorageService storage) {
        this.stages = stages.stream()
                .sorted(Comparator.comparingInt(s -> s.id().ordinal()))
                .toList();
        this.checkpoints = checkpoints;
        this.loader = loader;
        this.storage = storage;
    }

    public List<StageExecutor> stages() {
        return stages;
    }

    /**
     * Builds a context for previews: it contains everything stage fingerprints
     * need, but nothing is executed and no row is written. A requested profile is
     * applied as an override so the preview sees the exact fingerprint the
     * execution will compute after the profile has been persisted.
     */
    public JobContext previewContext(String lectureId, ProcessingProfile requested) {
        JobContext context = new JobContext(lectureId, new com.lectureprocessor.process.CancellationToken());
        context.setLecture(loader.lecture(lectureId));
        context.setSource(loader.source(lectureId));
        context.setArtifact(loader.artifact(lectureId));
        context.setProfileOverride(requested);
        return context;
    }

    public List<Evaluation> evaluate(JobContext context) {
        return stages.stream().map(stage -> evaluate(context, stage)).toList();
    }

    public Evaluation evaluate(JobContext context, StageExecutor stage) {
        String fingerprint;
        try {
            fingerprint = stage.inputFingerprint(context);
        } catch (RuntimeException e) {
            log.warn("Cannot compute fingerprint for stage {} of lecture {}: {}",
                    stage.id(), context.lectureId(), e.getMessage());
            return new Evaluation(stage.id(), null, false, true, Reason.NO_CHECKPOINT);
        }
        if (!stage.isRequired(context)) {
            return new Evaluation(stage.id(), fingerprint, true, false, Reason.NOT_REQUIRED);
        }
        Optional<StageCheckpoint> checkpoint = checkpoints.find(context.lectureId(), stage.id());
        if (checkpoint.isEmpty()) {
            return new Evaluation(stage.id(), fingerprint, false, true, Reason.NO_CHECKPOINT);
        }
        StageCheckpoint cp = checkpoint.get();
        if (cp.state() != StageState.COMPLETED) {
            return new Evaluation(stage.id(), fingerprint, false, true, Reason.NOT_COMPLETED);
        }
        if (fingerprint == null || !fingerprint.equals(cp.fingerprint())) {
            return new Evaluation(stage.id(), fingerprint, false, true, Reason.FINGERPRINT_CHANGED);
        }
        if (!cp.manifest().isValid(storage, context.lectureId())) {
            return new Evaluation(stage.id(), fingerprint, false, true, Reason.ARTIFACT_MISSING);
        }
        return new Evaluation(stage.id(), fingerprint, true, true, Reason.VALID);
    }

    /** Stages that would execute for the given context, in pipeline order. */
    public List<PipelineStage> plannedStages(JobContext context) {
        return evaluate(context).stream()
                .filter(evaluation -> !evaluation.reusable())
                .map(Evaluation::stage)
                .toList();
    }
}
