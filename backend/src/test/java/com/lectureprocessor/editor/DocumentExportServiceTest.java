package com.lectureprocessor.editor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.lectureprocessor.document.DocxExporter;
import com.lectureprocessor.document.MarkdownBuilder;
import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.Slide;
import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.multilingual.LectureTranslation;
import com.lectureprocessor.multilingual.SegmentTranslationStatus;
import com.lectureprocessor.multilingual.TranslationRepository;
import com.lectureprocessor.multilingual.TranslationSegment;
import com.lectureprocessor.pipeline.PipelineDataLoader;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.SegmentRepository;
import com.lectureprocessor.repository.SlideRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.support.TestProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.ObjectMapper;

/**
 * Revision-aware re-export: all variants render saved revisions, corrected text
 * appears in the DOCX, slides are preserved, a failed export never replaces the
 * previous good document, and no pipeline stage is involved.
 */
class DocumentExportServiceTest {

    private Path tempDir;
    private HikariDataSource dataSource;
    private LectureRepository lecturesRepo;
    private SegmentRepository segments;
    private TranslationRepository translations;
    private SlideRepository slidesRepo;
    private DocumentRevisionService revisions;
    private DocumentExportService exportService;
    private EditorContentCodec codec;
    private LectureEditorService editor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String lectureId = "l1";

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("export-test");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("test.db").toAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(config);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
        }
        lecturesRepo = new LectureRepository(jdbc);
        segments = new SegmentRepository(jdbc);
        translations = new TranslationRepository(jdbc, objectMapper);
        slidesRepo = new SlideRepository(jdbc);
        DocumentRevisionRepository revisionRepo = new DocumentRevisionRepository(jdbc);
        LectureExportRepository exportRepo = new LectureExportRepository(jdbc);
        codec = new EditorContentCodec(objectMapper);
        FileStorageService storage = new FileStorageService(
                TestProperties.defaults(tempDir.toString()));
        PipelineDataLoader loader = mock(PipelineDataLoader.class);
        EditorProperties properties = EditorProperties.defaults();
        revisions = new DocumentRevisionService(revisionRepo, codec, segments, translations,
                lecturesRepo, loader, storage, properties);

        LectureService lectureService = mock(LectureService.class);
        Instant now = Instant.now();
        Lecture lecture = new Lecture(lectureId, "lecture.mp4", "source.mp4", 1000, "video/mp4",
                JobStatus.COMPLETED, null, 5000L, false, true, null, null, null, null, now, now);
        lecturesRepo.save(lecture);
        when(lectureService.getRequired(lectureId)).thenReturn(lecture);
        editor = mock(LectureEditorService.class);
        when(editor.exportSourceHash(anyString(), any())).thenReturn("hash-1");
        exportService = new DocumentExportService(lectureService, lecturesRepo, revisions, codec,
                exportRepo, slidesRepo, new DocxExporter(), new MarkdownBuilder(), storage, editor);

        segments.replaceAll(lectureId, List.of(
                new TranscriptSegment(0, 0, 1000, "Hello world"),
                new TranscriptSegment(1, 1000, 2000, "Vector database")));
        Path slideFile = storage.slidesDir(lectureId).resolve("slide-1.jpg");
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "jpg", slideFile.toFile());
        slidesRepo.replaceAll(lectureId, List.of(
                new Slide(0, lectureId, 0, 0, slideFile.toAbsolutePath().toString(), "hash")));
        lectureRepositoryNotes("# Заголовок\n\nАбзац конспекта");
    }

    private void lectureRepositoryNotes(String markdown) {
        lectureRepositoryNotesRaw(markdown);
    }

    private void lectureRepositoryNotesRaw(String markdown) {
        Lecture lecture = lecturesRepo.findById(lectureId).orElseThrow();
        lecturesRepo.updateNotesMarkdown(lectureId, markdown);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private LectureTranslation seedTranslation(String translatedText) {
        LectureTranslation translation = translations.createOrGet(lectureId, "fp", "en", "ru",
                "ollama", "qwen3:1.7b", "v3", "v1", "settings", 2);
        translations.upsertSegments(translation.id(), List.of(
                new TranslationSegment(0, 0, 1000, "Hello world", translatedText,
                        SegmentTranslationStatus.TRANSLATED, "OK", null, null, 1),
                new TranslationSegment(1, 1000, 2000, "Vector database", "Векторная база",
                        SegmentTranslationStatus.TRANSLATED, "OK", null, null, 1)));
        return translations.findLatest(lectureId).orElseThrow();
    }

    private static String docxText(Path path) throws Exception {
        try (InputStream in = Files.newInputStream(path);
                XWPFDocument document = new XWPFDocument(in)) {
            return document.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText())
                    .collect(Collectors.joining("\n"));
        }
    }

    @Test
    void exportsOriginalFromLatestTranscriptRevision() throws Exception {
        LectureExport export = exportService.export(lectureId, ExportVariant.ORIGINAL);
        assertThat(export.status()).isEqualTo("COMPLETED");
        Path docx = tempDir.resolve("lectures").resolve(lectureId).resolve("notes.original.docx");
        assertThat(docxText(docx)).contains("Hello world").contains("Vector database");
        assertThat(Files.readString(tempDir.resolve("lectures").resolve(lectureId)
                .resolve("transcript.original.md"), StandardCharsets.UTF_8))
                .contains("Hello world");
    }

    @Test
    void russianExportUsesCorrectedHumanTranslation() throws Exception {
        LectureTranslation translation = seedTranslation("Привет мир");
        revisions.ensureInitial(lectureId, DocumentType.TRANSCRIPT);
        revisions.ensureInitial(lectureId, DocumentType.TRANSLATION);
        // Human correction appended as a new revision.
        List<TranslationSegment> stored = translations.segments(translation.id());
        List<TranslationSegment> corrected = List.of(stored.get(0).corrected("Привет, большой мир"),
                stored.get(1));
        translations.upsertSegments(translation.id(), corrected);
        revisions.append(lectureId, DocumentType.TRANSLATION, codec.encodeTranslation(corrected),
                EditSource.HUMAN, "ручная правка", null);

        LectureExport export = exportService.export(lectureId, ExportVariant.RUSSIAN);

        assertThat(export.status()).isEqualTo("COMPLETED");
        Path docx = tempDir.resolve("lectures").resolve(lectureId).resolve("notes.ru.docx");
        assertThat(docxText(docx)).contains("Привет, большой мир");
    }

    @Test
    void bilingualExportContainsBothLanguages() throws Exception {
        seedTranslation("Привет мир");
        exportService.export(lectureId, ExportVariant.BILINGUAL);
        Path docx = tempDir.resolve("lectures").resolve(lectureId)
                .resolve("notes.bilingual.docx");
        assertThat(docxText(docx)).contains("Hello world").contains("Привет мир");
    }

    @Test
    void notesExportPreservesSlideImages() throws Exception {
        revisions.ensureInitial(lectureId, DocumentType.NOTES);
        exportService.export(lectureId, ExportVariant.NOTES);
        Path docx = tempDir.resolve("lectures").resolve(lectureId).resolve("notes.docx");
        try (InputStream in = Files.newInputStream(docx);
                XWPFDocument document = new XWPFDocument(in)) {
            assertThat(document.getAllPictures()).hasSize(1);
        }
    }

    @Test
    void failedExportDoesNotReplacePreviousDocument() throws Exception {
        Path target = tempDir.resolve("lectures").resolve(lectureId).resolve("notes.ru.docx");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "GOOD-DOCUMENT", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> exportService.export(lectureId, ExportVariant.RUSSIAN))
                .isInstanceOf(EditorException.class);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("GOOD-DOCUMENT");
        assertThat(exportServiceExports()).anySatisfy(record ->
                assertThat(record.status()).isEqualTo("FAILED"));
    }

    private List<LectureExport> exportServiceExports() {
        // The failed record is reachable through the repository used by the service.
        LectureExportRepository repository = new LectureExportRepository(
                new JdbcTemplate(dataSource));
        return repository.findByLecture(lectureId, 10);
    }

    @Test
    void notesExportUsesEditedMarkdownRevision() throws Exception {
        revisions.ensureInitial(lectureId, DocumentType.NOTES);
        revisions.append(lectureId, DocumentType.NOTES, "# Новый заголовок\n\nНовый абзац",
                EditSource.HUMAN, "правка", null);
        exportService.export(lectureId, ExportVariant.NOTES);
        Path docx = tempDir.resolve("lectures").resolve(lectureId).resolve("notes.docx");
        assertThat(docxText(docx)).contains("Новый заголовок").contains("Новый абзац");
    }
}
