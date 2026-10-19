package com.lectureprocessor.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.repository.PipelineRunRepository;
import com.lectureprocessor.repository.StageCheckpointRepository;
import com.lectureprocessor.repository.TimingRepository;
import com.lectureprocessor.storage.FileStorageService;

/**
 * Cancellation invariants of the coordinator: a cancelled request must win over
 * a late-finishing worker, the active checkpoint must become terminal, and no
 * downstream stage may start.
 */
class PipelineCoordinatorCancellationTest {

    private final PipelineJobRepository jobRepository = mock(PipelineJobRepository.class);
    private final StageCheckpointRepository checkpointRepository = mock(StageCheckpointRepository.class);
    private final LectureRepository lectureRepository = mock(LectureRepository.class);
    private final PipelineDataLoader loader = mock(PipelineDataLoader.class);
    private final TimingRepository timings = mock(TimingRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final JobProgressRegistry progress = new JobProgressRegistry();
    private final PipelinePlanner planner = mock(PipelinePlanner.class);
    private final PipelineRunRepository runs = mock(PipelineRunRepository.class);

    private PipelineCoordinator coordinator(List<StageExecutor> stages) {
        when(planner.plannedStages(any())).thenAnswer(invocation -> stages.stream()
                .map(StageExecutor::id).toList());
        when(planner.evaluate(any(), any())).thenAnswer(invocation -> {
            StageExecutor stage = invocation.getArgument(1);
            return new PipelinePlanner.Evaluation(stage.id(), "fp-" + stage.id().name(),
                    false, true, PipelinePlanner.Reason.NO_CHECKPOINT);
        });
        when(runs.create(anyString(), any())).thenReturn("run-1");
        return new PipelineCoordinator(stages, jobRepository, checkpointRepository,
                lectureRepository, loader, progress, timings, storage, planner, runs);
    }

    private static Lecture lecture(String id, JobStatus status) {
        Instant now = Instant.now();
        return new Lecture(id, "lecture.mp4", "source.mp4", 10, "video/mp4", status, null,
                null, false, false, null, null, null, null, now, now);
    }

    private static PipelineJob job(String id, JobState state, PipelineStage stage, Instant lease) {
        Instant now = Instant.now();
        return new PipelineJob(id, state, stage, 1, "owner", lease, null, now, now);
    }

    private StageExecutor stage(PipelineStage id, Runnable onExecute) {
        StageExecutor stage = mock(StageExecutor.class);
        when(stage.id()).thenReturn(id);
        when(stage.inputFingerprint(any())).thenReturn("fp-" + id.name());
        when(stage.execute(any())).thenAnswer(invocation -> {
            onExecute.run();
            return StageResult.completed(id, ArtifactManifest.empty(), 1);
        });
        return stage;
    }

    @Test
    void cancelDuringLastStageNeverFinishesAsCompleted() {
        Lecture notStarted = lecture("l1", JobStatus.UPLOADED);
        when(loader.lecture("l1")).thenAnswer(invocation -> notStarted);
        when(jobRepository.claim(eq("l1"), anyString(), any())).thenReturn(true);
        when(jobRepository.find("l1")).thenReturn(Optional.empty());
        when(checkpointRepository.find(eq("l1"), any())).thenReturn(Optional.empty());

        AtomicReference<PipelineCoordinator> ref = new AtomicReference<>();
        StageExecutor onlyStage = stage(PipelineStage.EXPORT,
                () -> ref.get().cancel("l1"));
        ref.set(coordinator(List.of(onlyStage)));

        ref.get().run("l1");

        verify(jobRepository).finish("l1", JobState.CANCELLED, "Отменено пользователем");
        verify(jobRepository, never()).finish(eq("l1"), eq(JobState.COMPLETED), any());
        verify(checkpointRepository).cancelRunning("l1");
        ArgumentCaptor<Lecture> saved = ArgumentCaptor.forClass(Lecture.class);
        verify(lectureRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anySatisfy(value ->
                assertThat(value.status()).isEqualTo(JobStatus.CANCELLED));
    }

    @Test
    void cancelStopsDownstreamStages() {
        Lecture notStarted = lecture("l2", JobStatus.UPLOADED);
        when(loader.lecture("l2")).thenAnswer(invocation -> notStarted);
        when(jobRepository.claim(eq("l2"), anyString(), any())).thenReturn(true);
        when(jobRepository.find("l2")).thenReturn(Optional.empty());
        when(checkpointRepository.find(eq("l2"), any())).thenReturn(Optional.empty());

        AtomicReference<PipelineCoordinator> ref = new AtomicReference<>();
        StageExecutor first = stage(PipelineStage.TRANSCRIPTION, () -> ref.get().cancel("l2"));
        StageExecutor second = stage(PipelineStage.TEXT_PROCESSING, () -> {
        });
        ref.set(coordinator(List.of(first, second)));

        ref.get().run("l2");

        verify(second, never()).execute(any());
        verify(jobRepository).finish("l2", JobState.CANCELLED, "Отменено пользователем");
        verify(checkpointRepository).cancelRunning("l2");
    }

    @Test
    void cancelBeforeWorkerStartsIsConsumedByTheRun() {
        Lecture notStarted = lecture("l5", JobStatus.UPLOADED);
        when(loader.lecture("l5")).thenAnswer(invocation -> notStarted);
        when(jobRepository.find("l5")).thenReturn(Optional.of(
                job("l5", JobState.QUEUED, null, null)));
        when(jobRepository.claim(eq("l5"), anyString(), any())).thenReturn(true);
        when(checkpointRepository.find(eq("l5"), any())).thenReturn(Optional.empty());

        StageExecutor neverRuns = stage(PipelineStage.TRANSCRIPTION, () -> {
        });
        PipelineCoordinator coordinator = coordinator(List.of(neverRuns));

        assertThat(coordinator.cancel("l5")).isTrue();
        coordinator.run("l5");

        verify(neverRuns, never()).execute(any());
        verify(jobRepository).finish("l5", JobState.CANCELLED, "Отменено пользователем");
        verify(checkpointRepository).cancelRunning("l5");
    }

    @Test
    void cancelledJobCannotBeCancelledTwice() {
        when(jobRepository.find("l3")).thenReturn(Optional.of(
                job("l3", JobState.CANCELLED, PipelineStage.TRANSCRIPTION, null)));
        PipelineCoordinator coordinator = coordinator(List.<StageExecutor>of());

        assertThat(coordinator.cancel("l3")).isFalse();
        verify(jobRepository, never()).cancelStale(anyString(), any(), anyString());
    }

    @Test
    void staleRunningJobIsCancelledTogetherWithItsCheckpoint() {
        Lecture running = lecture("l4", JobStatus.TRANSCRIBING);
        when(jobRepository.find("l4")).thenReturn(Optional.of(job("l4", JobState.RUNNING,
                PipelineStage.TRANSCRIPTION, Instant.now().minusSeconds(60))));
        when(jobRepository.cancelStale(eq("l4"), any(), anyString())).thenReturn(1);
        when(lectureRepository.findById("l4")).thenReturn(Optional.of(running));

        PipelineCoordinator coordinator = coordinator(List.<StageExecutor>of());

        assertThat(coordinator.cancel("l4")).isTrue();
        verify(checkpointRepository).cancelRunning("l4");
        ArgumentCaptor<Lecture> saved = ArgumentCaptor.forClass(Lecture.class);
        verify(lectureRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(JobStatus.CANCELLED);
    }
}
