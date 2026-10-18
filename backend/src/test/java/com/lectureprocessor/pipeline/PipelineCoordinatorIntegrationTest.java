package com.lectureprocessor.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.llm.EmbeddingProvider;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmTaskSettingsService;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.MediaSourceRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.repository.StageCheckpointRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.text.PromptTemplates;
import com.lectureprocessor.transcription.TranscriptionService;
import com.lectureprocessor.video.FfmpegService;

/**
 * Full-context integration test of the resumable pipeline with external tools
 * stubbed. Verifies checkpoint reuse, invalidation and failure recovery against
 * a real SQLite database and real artifacts on disk.
 */
@SpringBootTest(properties = {
        "app.storage.root=target/it-pipeline",
        "spring.datasource.url=jdbc:sqlite:target/it-pipeline/it-pipeline.db",
        "spring.sql.init.mode=always"
})
class PipelineCoordinatorIntegrationTest {

    private static final String TRANSCRIPT_JSON = """
            {"transcription":[{"offsets":{"from":0,"to":5000},"text":" Введение в тему лекции."},
                              {"offsets":{"from":5000,"to":12000},"text":" Основные определения."}]}
            """;

    @Autowired
    private PipelineCoordinator coordinator;
    @Autowired
    private LectureRepository lectureRepository;
    @Autowired
    private MediaSourceRepository mediaSourceRepository;
    @Autowired
    private PipelineJobRepository jobRepository;
    @Autowired
    private StageCheckpointRepository checkpointRepository;
    @Autowired
    private FileStorageService storage;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private FfmpegService ffmpeg;
    @MockitoBean
    private TranscriptionService transcription;
    @MockitoBean
    private EmbeddingProvider embeddingProvider;
    @MockitoBean
    private LlmTaskSettingsService taskResolver;

    private final FakeLlmProvider llm = new FakeLlmProvider();

    private ResolvedLlmTask lectureTask() {
        LlmRunConfig config = new LlmRunConfig("ollama", "test-model", 0.1, 1536, 4096, false,
                PromptTemplates.STRUCTURED_NOTES);
        return new ResolvedLlmTask(LlmTaskType.LECTURE_PROCESSING, config, llm, config.toOptions(),
                false, null, false);
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM stage_checkpoint");
        jdbc.update("DELETE FROM pipeline_job");
        jdbc.update("DELETE FROM download_state");
        jdbc.update("DELETE FROM media_source");
        jdbc.update("DELETE FROM transcript_segment");
        jdbc.update("DELETE FROM lecture_chunk");
        jdbc.update("DELETE FROM slide");
        jdbc.update("DELETE FROM lecture");

        llm.setId("ollama");
        llm.setChatFunction((system, user) -> "## Раздел\nСодержание конспекта лекции.");
        when(taskResolver.resolve(any(LlmTaskType.class))).thenAnswer(invocation -> lectureTask());
        when(embeddingProvider.isAvailable()).thenReturn(true);
        when(embeddingProvider.id()).thenReturn("ollama");
        when(embeddingProvider.embed(any())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(t -> new float[32]).toList();
        });
        when(transcription.transcribe(any(), anyString())).thenReturn(new Transcript(
                List.of(new TranscriptSegment(0, 0, 5000, "Введение в тему лекции."),
                        new TranscriptSegment(1, 5000, 12000, "Основные определения.")),
                TRANSCRIPT_JSON));
        org.mockito.Mockito.doAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.createDirectories(output.getParent());
            Files.writeString(output, "RIFF....WAVE", StandardCharsets.UTF_8);
            return null;
        }).when(ffmpeg).extractAudio(any(), any());
        when(ffmpeg.probeDurationMs(any())).thenReturn(12_000L);
    }

    private String createLocalLecture(String id) throws Exception {
        Path source = storage.resolve(id, "source.mp4");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[2048]);
        Instant now = Instant.now();
        lectureRepository.save(new Lecture(id, "lecture.mp4", "source.mp4", 2048, "video/mp4",
                JobStatus.UPLOADED, null, null, false, false, null, null, null, null, now, now));
        mediaSourceRepository.save(MediaSource.local(id, "lecture.mp4"));
        return id;
    }

    @Test
    void completesFullLocalPipelineAndCreatesArtifacts() throws Exception {
        String id = createLocalLecture("it-full");

        coordinator.run(id);

        Lecture lecture = lectureRepository.findById(id).orElseThrow();
        assertThat(lecture.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(storage.resolve(id, "transcript.json")).exists();
        assertThat(storage.resolve(id, "transcript.md")).exists();
        assertThat(storage.resolve(id, "notes.md")).exists();
        assertThat(storage.resolve(id, "notes.docx")).exists();
        assertThat(Files.size(storage.resolve(id, "notes.docx"))).isPositive();
        assertThat(checkpointRepository.findByLecture(id))
                .allSatisfy(cp -> assertThat(cp.state()).isEqualTo(StageState.COMPLETED));
        assertThat(jobRepository.find(id).orElseThrow().state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void llmFailureDoesNotRerunWhisperOnRetry() throws Exception {
        String id = createLocalLecture("it-recovery");
        llm.setChatFunction((system, user) -> {
            throw new RuntimeException("Ollama недоступна");
        });

        coordinator.run(id);

        Lecture failed = lectureRepository.findById(id).orElseThrow();
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(checkpointRepository.find(id, PipelineStage.TRANSCRIPTION).orElseThrow().state())
                .isEqualTo(StageState.COMPLETED);
        assertThat(checkpointRepository.find(id, PipelineStage.TEXT_PROCESSING).orElseThrow().state())
                .isEqualTo(StageState.FAILED);

        // Recover: LLM works again.
        llm.setChatFunction((system, user) -> "## Раздел\nГотовый конспект.");
        coordinator.run(id);

        assertThat(lectureRepository.findById(id).orElseThrow().status()).isEqualTo(JobStatus.COMPLETED);
        // The expensive transcription ran exactly once across both runs.
        verify(transcription, times(1)).transcribe(any(), anyString());
        verify(ffmpeg, times(1)).extractAudio(any(), any());
    }

    @Test
    void changingPromptRerunsTextAndExportButNotTranscription() throws Exception {
        String id = createLocalLecture("it-invalidate");
        coordinator.run(id);
        assertThat(lectureRepository.findById(id).orElseThrow().status()).isEqualTo(JobStatus.COMPLETED);

        // Force a new text-processing fingerprint by changing the effective model config.
        jdbc.update("DELETE FROM app_setting");
        jdbc.update("""
                INSERT INTO app_setting (setting_key, setting_value, updated_at)
                VALUES ('llm.default-config',
                        '{"provider":"ollama","model":"test-model-2","temperature":0.1,"maxTokens":1536,'
                        || '"contextWindow":4096,"thinking":false,"promptTemplate":"structured-notes-v2"}',
                        ?)
                """, Instant.now().toString());

        coordinator.run(id);

        assertThat(lectureRepository.findById(id).orElseThrow().status()).isEqualTo(JobStatus.COMPLETED);
        // Transcription (and audio/ffmpeg) still ran only once.
        verify(transcription, times(1)).transcribe(any(), anyString());
        verify(ffmpeg, times(1)).extractAudio(any(), any());
    }

    @Test
    void secondCoordinatorRunAfterSuccessReusesEverything() throws Exception {
        String id = createLocalLecture("it-idempotent");
        coordinator.run(id);
        coordinator.run(id);

        verify(transcription, times(1)).transcribe(any(), anyString());
        verify(ffmpeg, times(1)).extractAudio(any(), any());
    }

    /**
     * A locally uploaded lecture has no media_source row until SOURCE_RESOLUTION
     * creates it. The second run must still reuse every checkpoint; before the
     * fingerprint fix the whole pipeline (including Whisper) re-ran.
     */
    @Test
    void localLectureWithoutMediaSourceRowReusesEverythingOnRerun() throws Exception {
        String id = "it-no-source-row";
        Path source = storage.resolve(id, "source.mp4");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[2048]);
        Instant now = Instant.now();
        lectureRepository.save(new Lecture(id, "lecture.mp4", "source.mp4", 2048, "video/mp4",
                JobStatus.UPLOADED, null, null, false, false, null, null, null, null, now, now));
        assertThat(mediaSourceRepository.findByLecture(id)).isEmpty();

        coordinator.run(id);
        coordinator.run(id);

        verify(transcription, times(1)).transcribe(any(), anyString());
        verify(ffmpeg, times(1)).extractAudio(any(), any());
        assertThat(mediaSourceRepository.findByLecture(id)).isPresent();
    }

    /** Sanity check that the lease repository rejects a duplicate active claim. */
    @Test
    void leasePreventsConcurrentClaims() throws Exception {
        String id = createLocalLecture("it-lease");
        jobRepository.ensure(id);
        Instant future = Instant.now().plus(Duration.ofMinutes(30));
        assertThat(jobRepository.claim(id, "owner-a", future)).isTrue();
        assertThat(jobRepository.claim(id, "owner-b", future)).isFalse();
    }
}
