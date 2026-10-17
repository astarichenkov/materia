package com.lectureprocessor.multilingual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.lectureprocessor.pipeline.PipelineCoordinator;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.repository.StageCheckpointRepository;
import com.lectureprocessor.pipeline.StageState;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.MediaSourceRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.text.PromptTemplates;
import com.lectureprocessor.transcription.TranscriptionService;
import com.lectureprocessor.video.FfmpegService;

/**
 * End-to-end (external tools stubbed) verification of the multilingual pipeline:
 * English transcript -> detected language -> translated segments -> Russian
 * notes -> original/translated/bilingual documents, with resume semantics.
 */
@SpringBootTest(properties = {
        "app.storage.root=target/it-multilingual",
        "spring.datasource.url=jdbc:sqlite:target/it-multilingual/it-multilingual.db",
        "spring.sql.init.mode=always"
})
class TranslationPipelineIntegrationTest {

    private static final String TRANSCRIPT_JSON = """
            {"result":{"language":"en"},"transcription":[
              {"offsets":{"from":0,"to":4000},"text":"First segment about embeddings."},
              {"offsets":{"from":4000,"to":9000},"text":"Second segment about retrieval."}]}
            """;

    @Autowired
    private PipelineCoordinator coordinator;
    @Autowired
    private LectureRepository lectureRepository;
    @Autowired
    private MediaSourceRepository mediaSourceRepository;
    @Autowired
    private StageCheckpointRepository checkpoints;
    @Autowired
    private LectureLanguageRepository languageRepository;
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

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM stage_checkpoint");
        jdbc.update("DELETE FROM pipeline_job");
        jdbc.update("DELETE FROM media_source");
        jdbc.update("DELETE FROM translation_segment");
        jdbc.update("DELETE FROM lecture_translation");
        jdbc.update("DELETE FROM lecture_language");
        jdbc.update("DELETE FROM lecture_topic");
        jdbc.update("DELETE FROM lecture_similarity");
        jdbc.update("DELETE FROM transcript_segment");
        jdbc.update("DELETE FROM lecture_chunk");
        jdbc.update("DELETE FROM lecture");
        jdbc.update("DELETE FROM app_setting");

        llm.setId("ollama");
        llm.setDetailed(true);
        llm.setChatFunction((system, user) -> {
            if (system.contains("переводчик")) {
                return translationJson(user);
            }
            return "## Раздел\nРусский структурированный конспект.";
        });
        when(taskResolver.resolve(any(LlmTaskType.class))).thenAnswer(invocation -> {
            LlmTaskType task = invocation.getArgument(0);
            String prompt = task == LlmTaskType.LECTURE_TRANSLATION
                    ? PromptTemplates.LECTURE_TRANSLATION : PromptTemplates.STRUCTURED_NOTES;
            LlmRunConfig config = new LlmRunConfig("ollama", "test-model", 0.1, 2048, 4096, false,
                    prompt);
            return new ResolvedLlmTask(task, config, llm, config.toOptions(), false, null, false);
        });
        when(embeddingProvider.isAvailable()).thenReturn(true);
        when(embeddingProvider.id()).thenReturn("ollama");
        when(embeddingProvider.embed(any())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(FakeLlmProvider::embedOne).toList();
        });
        when(transcription.transcribe(any(), anyString())).thenReturn(new Transcript(List.of(
                new TranscriptSegment(0, 0, 4000, "First segment about embeddings."),
                new TranscriptSegment(1, 4000, 9000, "Second segment about retrieval.")),
                TRANSCRIPT_JSON));
        org.mockito.Mockito.doAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.createDirectories(output.getParent());
            Files.writeString(output, "RIFF....WAVE", StandardCharsets.UTF_8);
            return null;
        }).when(ffmpeg).extractAudio(any(), any());
        when(ffmpeg.probeDurationMs(any())).thenReturn(9000L);
    }

    @Test
    void createsRussianAndBilingualDocumentsAndResumesWithoutRetranslating() throws Exception {
        String id = "it-multilingual";
        Path source = storage.resolve(id, "source.mp4");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[1024]);
        Instant now = Instant.now();
        lectureRepository.save(new Lecture(id, "english-lecture.mp4", "source.mp4", 1024,
                "video/mp4", JobStatus.UPLOADED, null, null, false, false, null, null, null, null,
                now, now));
        mediaSourceRepository.save(MediaSource.local(id, "english-lecture.mp4"));

        coordinator.run(id);

        Lecture lecture = lectureRepository.findById(id).orElseThrow();
        assertThat(lecture.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(languageRepository.find(id).orElseThrow().detectedLanguage()).isEqualTo("en");
        assertThat(checkpoints.find(id, PipelineStage.TRANSLATION).orElseThrow().state())
                .isEqualTo(StageState.COMPLETED);
        assertThat(storage.resolve(id, "translation.json")).exists();
        assertThat(Files.readString(storage.resolve(id, "transcript.original.md"),
                StandardCharsets.UTF_8)).contains("First segment about embeddings");
        assertThat(Files.readString(storage.resolve(id, "transcript.ru.md"),
                StandardCharsets.UTF_8)).contains("Перевод");
        assertThat(Files.readString(storage.resolve(id, "transcript.bilingual.md"),
                StandardCharsets.UTF_8)).contains("First segment about embeddings", "RU:");
        assertThat(storage.resolve(id, "notes.ru.docx")).exists();
        assertThat(storage.resolve(id, "notes.bilingual.docx")).exists();
        assertThat(storage.resolve(id, "notes.original.docx")).exists();
        assertThat(Files.readString(storage.resolve(id, "notes.md"), StandardCharsets.UTF_8))
                .contains("Русский структурированный конспект");

        int calls = llm.chatCalls().size();
        coordinator.run(id);
        assertThat(llm.chatCalls()).hasSize(calls);
    }

    @Test
    void reusesSuccessfulWhisperWhenTranslationFailsAndResumesOnlyTranslation() throws Exception {
        String id = "it-multilingual-resume";
        Path source = storage.resolve(id, "source.mp4");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[1024]);
        Instant now = Instant.now();
        lectureRepository.save(new Lecture(id, "english-lecture.mp4", "source.mp4", 1024,
                "video/mp4", JobStatus.UPLOADED, null, null, false, false, null, null, null, null,
                now, now));
        mediaSourceRepository.save(MediaSource.local(id, "english-lecture.mp4"));

        llm.setChatFunction((system, user) -> {
            if (system.contains("переводчик")) {
                throw new RuntimeException("DeepSeek недоступен");
            }
            return "## Раздел\nКонспект.";
        });
        coordinator.run(id);

        assertThat(checkpoints.find(id, PipelineStage.TRANSCRIPTION).orElseThrow().state())
                .isEqualTo(StageState.COMPLETED);
        assertThat(checkpoints.find(id, PipelineStage.TRANSLATION).orElseThrow().state())
                .isEqualTo(StageState.FAILED);
        assertThat(checkpoints.find(id, PipelineStage.TEXT_PROCESSING).orElseThrow().state())
                .isEqualTo(StageState.COMPLETED);
        int whisperCalls = org.mockito.Mockito.mockingDetails(transcription).getInvocations().size();

        llm.setChatFunction((system, user) -> system.contains("переводчик")
                ? translationJson(user) : "## Раздел\nРусский конспект.");
        coordinator.run(id);

        assertThat(lectureRepository.findById(id).orElseThrow().status())
                .isEqualTo(JobStatus.COMPLETED);
        assertThat(org.mockito.Mockito.mockingDetails(transcription).getInvocations())
                .hasSize(whisperCalls);
        assertThat(checkpoints.find(id, PipelineStage.TRANSLATION).orElseThrow().state())
                .isEqualTo(StageState.COMPLETED);
    }

    private static String translationJson(String userPrompt) {
        StringBuilder json = new StringBuilder("{\"segments\":[");
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(userPrompt);
        int index = 0;
        while (matcher.find()) {
            if (index++ > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(matcher.group(1)).append(",\"text\":\"Перевод ")
                    .append(matcher.group(1)).append("\"}");
        }
        return json.append("]}").toString();
    }
}
