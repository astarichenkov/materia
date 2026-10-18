package com.lectureprocessor.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.repository.DownloadStateRepository;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.repository.StageCheckpointRepository;

/**
 * Historical rows can be contradictory (job CANCELLED + stage RUNNING, or the
 * reverse). The view must resolve the contradiction without rewriting the
 * database and without hiding the raw values.
 */
class JobStatusServiceTest {

    private final LectureRepository lectureRepository = mock(LectureRepository.class);
    private final PipelineJobRepository jobRepository = mock(PipelineJobRepository.class);
    private final StageCheckpointRepository checkpointRepository = mock(StageCheckpointRepository.class);
    private final DownloadStateRepository downloadStateRepository = mock(DownloadStateRepository.class);
    private final JobProgressRegistry progressRegistry = new JobProgressRegistry();

    private JobStatusService service;

    @BeforeEach
    void setUp() {
        service = new JobStatusService(lectureRepository, jobRepository, checkpointRepository,
                downloadStateRepository, progressRegistry);
        when(downloadStateRepository.find(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
    }

    private static Lecture lecture(String id, JobStatus status) {
        Instant now = Instant.now();
        return new Lecture(id, "lecture.mp4", "source.mp4", 10, "video/mp4", status, null,
                null, false, false, null, null, null, null, now, now);
    }

    private static PipelineJob job(String id, JobState state, PipelineStage stage) {
        Instant now = Instant.now();
        return new PipelineJob(id, state, stage, 1, null, null, null, now, now);
    }

    private static StageCheckpoint checkpoint(String id, PipelineStage stage, StageState state) {
        Instant now = Instant.now();
        return new StageCheckpoint(id, stage, state, "fp", 1, now, null, null,
                ArtifactManifest.empty());
    }

    @Test
    void cancelledJobWithRunningStageIsShownAsCancelledTerminalTask() {
        when(lectureRepository.findById("l1"))
                .thenReturn(Optional.of(lecture("l1", JobStatus.CANCELLED)));
        when(jobRepository.find("l1"))
                .thenReturn(Optional.of(job("l1", JobState.CANCELLED, PipelineStage.TRANSCRIPTION)));
        when(checkpointRepository.findByLecture("l1")).thenReturn(List.of(
                checkpoint("l1", PipelineStage.SOURCE_RESOLUTION, StageState.COMPLETED),
                checkpoint("l1", PipelineStage.DOWNLOADING, StageState.COMPLETED),
                checkpoint("l1", PipelineStage.MEDIA_VALIDATION, StageState.COMPLETED),
                checkpoint("l1", PipelineStage.AUDIO_EXTRACTION, StageState.COMPLETED),
                checkpoint("l1", PipelineStage.TRANSCRIPTION, StageState.RUNNING)));

        JobStatusView view = service.view("l1");

        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.terminal()).isTrue();
        assertThat(view.progressPercent()).isEqualTo(40);
        JobStatusView.StageView transcription = view.stages().stream()
                .filter(stage -> stage.stage().equals("TRANSCRIPTION")).findFirst().orElseThrow();
        assertThat(transcription.state()).isEqualTo("CANCELLED");
        assertThat(transcription.rawState()).isEqualTo("RUNNING");
        JobStatusView.StageView pending = view.stages().stream()
                .filter(stage -> stage.stage().equals("EXPORT")).findFirst().orElseThrow();
        assertThat(pending.state()).isEqualTo("SKIPPED");
        assertThat(pending.rawState()).isEqualTo("PENDING");
        assertThat(view.rawStatus()).isEqualTo("CANCELLED");
        assertThat(view.rawJobState()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelledLectureWithStaleRunningJobRowIsStillTerminal() {
        when(lectureRepository.findById("l2"))
                .thenReturn(Optional.of(lecture("l2", JobStatus.CANCELLED)));
        when(jobRepository.find("l2"))
                .thenReturn(Optional.of(job("l2", JobState.RUNNING, PipelineStage.TRANSCRIPTION)));
        when(checkpointRepository.findByLecture("l2")).thenReturn(List.of(
                checkpoint("l2", PipelineStage.TRANSCRIPTION, StageState.RUNNING)));

        JobStatusView view = service.view("l2");

        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.jobState()).isEqualTo("CANCELLED");
        assertThat(view.terminal()).isTrue();
        assertThat(view.stages().get(4).state()).isEqualTo("CANCELLED");
    }

    @Test
    void interruptedRestartShowsInterruptedStage() {
        when(lectureRepository.findById("l3"))
                .thenReturn(Optional.of(lecture("l3", JobStatus.INTERRUPTED)));
        when(jobRepository.find("l3"))
                .thenReturn(Optional.of(job("l3", JobState.INTERRUPTED, PipelineStage.TEXT_PROCESSING)));
        when(checkpointRepository.findByLecture("l3")).thenReturn(List.of(
                checkpoint("l3", PipelineStage.TEXT_PROCESSING, StageState.INTERRUPTED)));

        JobStatusView view = service.view("l3");

        assertThat(view.status()).isEqualTo("INTERRUPTED");
        assertThat(view.terminal()).isTrue();
        JobStatusView.StageView stage = view.stages().get(6);
        assertThat(stage.state()).isEqualTo("INTERRUPTED");
        assertThat(stage.rawState()).isEqualTo("INTERRUPTED");
    }

    @Test
    void runningJobStaysActive() {
        when(lectureRepository.findById("l4"))
                .thenReturn(Optional.of(lecture("l4", JobStatus.TRANSCRIBING)));
        when(jobRepository.find("l4"))
                .thenReturn(Optional.of(job("l4", JobState.RUNNING, PipelineStage.TRANSCRIPTION)));
        when(checkpointRepository.findByLecture("l4")).thenReturn(List.of(
                checkpoint("l4", PipelineStage.TRANSCRIPTION, StageState.RUNNING)));

        JobStatusView view = service.view("l4");

        assertThat(view.status()).isEqualTo("TRANSCRIBING");
        assertThat(view.terminal()).isFalse();
        assertThat(view.progressPercent()).isEqualTo(50);
        assertThat(view.stages().get(4).state()).isEqualTo("RUNNING");
        assertThat(view.stages().get(9).state()).isEqualTo("PENDING");
    }
}
