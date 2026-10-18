package com.lectureprocessor.editor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageService;
import com.lectureprocessor.multilingual.LectureTranslation;
import com.lectureprocessor.multilingual.RetranslationSuggestion;
import com.lectureprocessor.multilingual.SegmentRetranslationService;
import com.lectureprocessor.multilingual.SegmentTranslationStatus;
import com.lectureprocessor.multilingual.TranslationRepository;
import com.lectureprocessor.multilingual.TranslationSegment;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.pipeline.PipelineDataLoader;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.repository.AppSettingRepository;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.SegmentRepository;
import com.lectureprocessor.repository.SlideRepository;
import com.lectureprocessor.repository.StageCheckpointRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.FixedTaskResolver;
import com.lectureprocessor.support.TestProperties;
import com.lectureprocessor.text.StructuredNotes;
import com.lectureprocessor.text.TextProcessingService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end persistence tests for the lecture editor: revisions, optimistic
 * locking, human priority, staleness, restore and notes regeneration against a
 * real SQLite schema and real files.
 */
class LectureEditorServiceTest {

    private Path tempDir;
    private HikariDataSource dataSource;
    private LectureRepository lecturesRepo;
    private SegmentRepository segments;
    private TranslationRepository translations;
    private DocumentRevisionRepository revisionRepo;
    private DocumentStateRepository states;
    private StageCheckpointRepository checkpoints;
    private LectureEditorService service;
    private LectureService lectureService;
    private LectureLanguageService languages;
    private TextProcessingService textProcessing;
    private SegmentRetranslationService retranslation;
    private FakeLlmProvider llm;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String lectureId = "l1";

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("editor-service-test");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("test.db").toAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(config);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
            // schema.sql intentionally holds the base tables only; the ingestion
            // tables come from migrations V2 (see AGENTS.md).
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V2__week7_ingestion.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V9__day35_lecture_editor.sql"));
        }
        lecturesRepo = new LectureRepository(jdbc);
        segments = new SegmentRepository(jdbc);
        translations = new TranslationRepository(jdbc, objectMapper);
        SlideRepository slides = new SlideRepository(jdbc);
        revisionRepo = new DocumentRevisionRepository(jdbc);
        states = new DocumentStateRepository(jdbc);
        SegmentCorrectionRepository corrections = new SegmentCorrectionRepository(jdbc);
        LectureExportRepository exports = new LectureExportRepository(jdbc);
        checkpoints = new StageCheckpointRepository(jdbc, objectMapper);
        AppSettingRepository settings = new AppSettingRepository(jdbc);
        EditorProperties properties = new EditorProperties(true, 10, 100, 4000, 50, 200,
                true, 10, 20000);
        EditorContentCodec codec = new EditorContentCodec(objectMapper);
        FileStorageService storage = new FileStorageService(
                TestProperties.defaults(tempDir.toString()));
        PipelineDataLoader loader = mock(PipelineDataLoader.class);
        DocumentRevisionService revisions = new DocumentRevisionService(revisionRepo, codec,
                segments, translations, lecturesRepo, loader, storage, properties);
        lectureService = mock(LectureService.class);
        languages = mock(LectureLanguageService.class);
        textProcessing = mock(TextProcessingService.class);
        retranslation = mock(SegmentRetranslationService.class);
        llm = new FakeLlmProvider();
        llm.setCloud(true);
        LlmTaskResolver resolver = new FixedTaskResolver(llm);
        service = new LectureEditorService(lectureService, lecturesRepo, segments, translations,
                slides, revisions, states, corrections, exports, retranslation, languages,
                textProcessing, resolver, loader, storage, checkpoints,
                new SafeMarkdownRenderer(), settings, properties, codec);

        Instant now = Instant.now();
        Lecture lecture = new Lecture(lectureId, "lecture.mp4", "source.mp4", 1000, "video/mp4",
                JobStatus.COMPLETED, null, 5000L, false, true, null, null, "# Старый конспект\n\nтекст",
                null, now, now);
        lecturesRepo.save(lecture);
        when(lectureService.getRequired(lectureId)).thenReturn(lecture);
        when(languages.get(lectureId)).thenReturn(LectureLanguage.defaults(lectureId, "AUTO",
                "TRANSLATE_TO_RUSSIAN", "ru", true));
        segments.replaceAll(lectureId, List.of(
                new TranscriptSegment(0, 0, 1000, "Hello world"),
                new TranscriptSegment(1, 1000, 2000, "Vector database")));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private LectureTranslation seedTranslation(SegmentTranslationStatus status) {
        LectureTranslation translation = translations.createOrGet(lectureId, "fp", "en", "ru",
                "ollama", "qwen3:1.7b", "v3", "v1", "settings", 2);
        translations.upsertSegments(translation.id(), List.of(
                new TranslationSegment(0, 0, 1000, "Hello world", "Привет мир", status, "OK",
                        status == SegmentTranslationStatus.WARNING ? "мало кириллицы" : null,
                        null, 1),
                new TranslationSegment(1, 1000, 2000, "Vector database", "Векторная база",
                        SegmentTranslationStatus.TRANSLATED, "OK", null, null, 1)));
        return translations.findLatest(lectureId).orElseThrow();
    }

    @Test
    void initialRevisionsAreCreatedFromPipelineOutput() {
        EditorView view = service.view(lectureId);
        assertThat(view.transcriptSegments()).hasSize(2);
        assertThat(service.latestRevisionNumber(lectureId, DocumentType.TRANSCRIPT)).isEqualTo(1);
        assertThat(service.latestRevisionNumber(lectureId, DocumentType.NOTES)).isEqualTo(1);
        assertThat(view.quality().totalSegments()).isEqualTo(2);
    }

    @Test
    void savesTranscriptAsRevisionAndMarksTranslationStale() {
        LectureTranslation translation = seedTranslation(SegmentTranslationStatus.TRANSLATED);
        EditorRequests.TranscriptSave request = new EditorRequests.TranscriptSave(1,
                List.of(new EditorRequests.SegmentEdit(0, "Hello brave world")), "правка термина");

        LectureDocumentRevision revision = service.saveTranscript(lectureId, request);

        assertThat(revision.revisionNumber()).isEqualTo(2);
        assertThat(revision.editSource()).isEqualTo(EditSource.HUMAN);
        assertThat(segments.findByLecture(lectureId).get(0).text()).isEqualTo("Hello brave world");
        List<TranslationSegment> stored = translations.segments(translation.id());
        assertThat(stored.get(0).status()).isEqualTo(SegmentTranslationStatus.STALE);
        assertThat(stored.get(1).status()).isEqualTo(SegmentTranslationStatus.TRANSLATED);
        assertThat(states.findByLecture(lectureId).get(DocumentType.NOTES).stale()).isTrue();
        assertThat(states.findByLecture(lectureId).get(DocumentType.TRANSLATION).stale()).isTrue();
        // Text processing + export must re-run later, Whisper must not.
        assertThat(checkpoints.find(lectureId, PipelineStage.TEXT_PROCESSING)).isEmpty();
    }

    @Test
    void rejectsSaveFromStaleRevision() {
        service.view(lectureId);
        EditorRequests.TranscriptSave request = new EditorRequests.TranscriptSave(0,
                List.of(new EditorRequests.SegmentEdit(0, "text")), null);
        assertThatThrownBy(() -> service.saveTranscript(lectureId, request))
                .isInstanceOf(EditorException.class)
                .satisfies(e -> assertThat(((EditorException) e).code())
                        .isEqualTo("REVISION_CONFLICT"));
    }

    @Test
    void translationEditCreatesHumanRevisionAndSurvivesReload() {
        seedTranslation(SegmentTranslationStatus.WARNING);
        service.view(lectureId);
        LectureDocumentRevision revision = service.saveTranslation(lectureId,
                new EditorRequests.TranslationSave(1,
                        List.of(new EditorRequests.TranslationEdit(0, "Привет, большой мир", null,
                                null, false, "уточнение")), "ручная правка"));

        assertThat(revision.revisionNumber()).isEqualTo(2);
        assertThat(revision.editSource()).isEqualTo(EditSource.HUMAN);
        TranslationSegment segment = translations.findLatest(lectureId).stream()
                .flatMap(t -> translations.segments(t.id()).stream())
                .filter(s -> s.sequence() == 0).findFirst().orElseThrow();
        assertThat(segment.status()).isEqualTo(SegmentTranslationStatus.MANUALLY_CORRECTED);
        assertThat(segment.translatedText()).isEqualTo("Привет, большой мир");
        assertThat(states.findByLecture(lectureId).get(DocumentType.NOTES).stale()).isTrue();

        // A fresh read (as after a restart) still sees the correction.
        EditorView reloaded = service.view(lectureId);
        assertThat(reloaded.translation().segments().get(0).translatedText())
                .isEqualTo("Привет, большой мир");
        assertThat(reloaded.quality().manuallyCorrected()).isEqualTo(1);
    }

    @Test
    void markReviewedClearsWarningWithoutChangingText() {
        seedTranslation(SegmentTranslationStatus.WARNING);
        service.view(lectureId);
        service.markReviewed(lectureId, 0);
        TranslationSegment segment = translations.segments(translations.findLatest(lectureId)
                        .orElseThrow().id()).get(0);
        assertThat(segment.status()).isEqualTo(SegmentTranslationStatus.REVIEWED);
        assertThat(segment.translatedText()).isEqualTo("Привет мир");
        assertThat(service.view(lectureId).quality().reviewed()).isEqualTo(1);
        assertThat(service.view(lectureId).quality().warnings()).isZero();
    }

    @Test
    void restoreCreatesNewRevisionAndRevertsContent() {
        service.view(lectureId);
        service.saveTranscript(lectureId, new EditorRequests.TranscriptSave(1,
                List.of(new EditorRequests.SegmentEdit(0, "Changed text")), null));
        LectureDocumentRevision first = service.history(lectureId, DocumentType.TRANSCRIPT, 10)
                .stream().filter(r -> r.revisionNumber() == 1).findFirst().orElseThrow();

        LectureDocumentRevision restored = service.restore(lectureId, DocumentType.TRANSCRIPT,
                first.id());

        assertThat(restored.revisionNumber()).isEqualTo(3);
        assertThat(segments.findByLecture(lectureId).get(0).text()).isEqualTo("Hello world");
    }

    @Test
    void notesSaveAndSafePreviewEscapeHtml() {
        service.view(lectureId);
        String markdown = "## Заголовок\n\n<script>alert('xss')</script>\n\n"
                + "[ok](https://example.com) [bad](javascript:alert(1))";
        service.saveNotes(lectureId, new EditorRequests.NotesSave(1, markdown, "правка"));

        String html = service.renderMarkdown(lectureId, markdown);
        assertThat(html).doesNotContain("<script");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("href=\"https://example.com\"");
        assertThat(html).doesNotContain("href=\"javascript:");
        assertThat(lecturesRepo.findById(lectureId).orElseThrow().notesMarkdown()).contains("Заголовок");
    }

    @Test
    void regenerateNotesAppendsRevisionWithoutLosingHumanEdit() {
        service.view(lectureId);
        service.saveNotes(lectureId, new EditorRequests.NotesSave(1, "# Ручной конспект", null));
        when(textProcessing.generateStructuredNotes(any(), any(), any(), anyString()))
                .thenReturn(new StructuredNotes("# Новый конспект\n\nтекст", "резюме", List.of()));

        LectureDocumentRevision revision = service.regenerateNotes(lectureId);

        assertThat(revision.editSource()).isEqualTo(EditSource.AI_ASSISTED);
        assertThat(revision.content()).contains("Новый конспект");
        List<LectureDocumentRevision> history = service.history(lectureId, DocumentType.NOTES, 10);
        assertThat(history).anySatisfy(r -> {
            assertThat(r.editSource()).isEqualTo(EditSource.HUMAN);
            assertThat(r.content()).isEqualTo("# Ручной конспект");
        });
        assertThat(history).anySatisfy(r -> assertThat(r.revisionNumber()).isEqualTo(1));
    }

    @Test
    void cloudRetryRequiresExplicitOptIn() {
        seedTranslation(SegmentTranslationStatus.WARNING);
        when(retranslation.suggest(lectureId, 0)).thenReturn(new RetranslationSuggestion(0,
                "Привет мир", "Привет, мир", "deepseek", "deepseek-flash", "OK", null, true, 10, 5));

        assertThatThrownBy(() -> service.previewRetranslation(lectureId, 0))
                .isInstanceOf(EditorException.class)
                .satisfies(e -> assertThat(((EditorException) e).code()).isEqualTo("FORBIDDEN"));

        service.setAllowCloudRetry(true);
        assertThat(service.previewRetranslation(lectureId, 0).suggestion().suggestedText())
                .isEqualTo("Привет, мир");
    }

    @Test
    void validationRejectsOversizedAndEmptyText() {
        service.view(lectureId);
        assertThatThrownBy(() -> service.saveTranscript(lectureId,
                new EditorRequests.TranscriptSave(1,
                        List.of(new EditorRequests.SegmentEdit(0, " ".repeat(101))), null)))
                .isInstanceOf(EditorException.class)
                .satisfies(e -> assertThat(((EditorException) e).code()).isEqualTo("VALIDATION"));
        assertThatThrownBy(() -> service.saveTranscript(lectureId,
                new EditorRequests.TranscriptSave(1,
                        List.of(new EditorRequests.SegmentEdit(0, "   ")), null)))
                .isInstanceOf(EditorException.class);
    }

    @Test
    void unknownSegmentIsRejected() {
        service.view(lectureId);
        assertThatThrownBy(() -> service.saveTranscript(lectureId,
                new EditorRequests.TranscriptSave(1,
                        List.of(new EditorRequests.SegmentEdit(99, "text")), null)))
                .isInstanceOf(EditorException.class)
                .satisfies(e -> assertThat(((EditorException) e).code()).isEqualTo("VALIDATION"));
    }

    @Test
    void rejectStaleRevisionOnNotesSaveToo() {
        service.view(lectureId);
        assertThatThrownBy(() -> service.saveNotes(lectureId,
                new EditorRequests.NotesSave(5, "# new", null)))
                .isInstanceOf(EditorException.class)
                .satisfies(e -> assertThat(((EditorException) e).code()).isEqualTo("REVISION_CONFLICT"));
    }

    @Test
    void revisionRetentionKeepsOriginalRevision() {
        service.view(lectureId);
        int expected = 1;
        for (int i = 0; i < 15; i++) {
            service.saveTranscript(lectureId, new EditorRequests.TranscriptSave(expected,
                    List.of(new EditorRequests.SegmentEdit(0, "text " + i)), null));
            expected++;
        }
        // Saving never deletes history implicitly.
        assertThat(service.history(lectureId, DocumentType.TRANSCRIPT, 100)).hasSize(16);
        // Cleanup is an explicit operation and always keeps the original revision.
        int removed = service.pruneRevisions(lectureId, DocumentType.TRANSCRIPT, 10);
        assertThat(removed).isPositive();
        List<LectureDocumentRevision> history = service.history(lectureId,
                DocumentType.TRANSCRIPT, 100);
        assertThat(history).hasSizeLessThanOrEqualTo(11);
        assertThat(history).anySatisfy(r -> assertThat(r.revisionNumber()).isEqualTo(1));
    }
}
