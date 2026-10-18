package com.lectureprocessor.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.document.DocxExporter;
import com.lectureprocessor.document.MarkdownBuilder;
import com.lectureprocessor.document.SlideImage;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.Slide;
import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.multilingual.TranslationSegment;
import com.lectureprocessor.pipeline.PipelineDataLoader;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.SlideRepository;
import com.lectureprocessor.storage.FileStorageService;

/**
 * Revision-aware re-export workflow.
 *
 * <pre>
 *   saved revisions -> load content -> load existing slides -> Markdown + DOCX
 *                   -> atomic replace -> versioned export record
 * </pre>
 *
 * <p>This service never downloads media, runs FFmpeg/Whisper, re-translates or
 * re-processes text. It renders only what the editor has already saved.</p>
 */
@Service
public class DocumentExportService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExportService.class);

    private final LectureService lectures;
    private final LectureRepository lectureRepository;
    private final DocumentRevisionService revisions;
    private final EditorContentCodec codec;
    private final LectureExportRepository exports;
    private final SlideRepository slides;
    private final DocxExporter docxExporter;
    private final MarkdownBuilder markdownBuilder;
    private final FileStorageService storage;
    private final LectureEditorService editor;

    public DocumentExportService(LectureService lectures, LectureRepository lectureRepository,
            DocumentRevisionService revisions, EditorContentCodec codec,
            LectureExportRepository exports, SlideRepository slides, DocxExporter docxExporter,
            MarkdownBuilder markdownBuilder, FileStorageService storage,
            LectureEditorService editor) {
        this.lectures = lectures;
        this.lectureRepository = lectureRepository;
        this.revisions = revisions;
        this.codec = codec;
        this.exports = exports;
        this.slides = slides;
        this.docxExporter = docxExporter;
        this.markdownBuilder = markdownBuilder;
        this.storage = storage;
        this.editor = editor;
    }

    public LectureExport export(String lectureId, ExportVariant variant) {
        Lecture lecture = lectures.getRequired(lectureId);
        String title = "Конспект: " + (lecture.originalFilename() == null
                || lecture.originalFilename().isBlank() ? lecture.id() : lecture.originalFilename());
        List<SlideImage> slideImages = existingSlides(lectureId);
        Long transcriptRevisionId = null;
        Long translationRevisionId = null;
        Long notesRevisionId = null;
        Path target = storage.resolve(lectureId, variant.docxArtifact());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            switch (variant) {
                case ORIGINAL -> {
                    LectureDocumentRevision transcript = ensure(lectureId, DocumentType.TRANSCRIPT);
                    transcriptRevisionId = transcript == null ? null : transcript.id();
                    List<TranscriptSegment> segments = transcriptSegments(lectureId);
                    if (segments.isEmpty()) {
                        throw EditorException.validation("Нет расшифровки для экспорта");
                    }
                    docxExporter.exportTranscript(temp, "Расшифровка: " + title,
                            segments, slideImages, null);
                    writeStringAtomic(storage.resolve(lectureId, variant.markdownArtifact()),
                            markdownBuilder.transcriptMarkdown(lecture,
                                    new Transcript(segments, ""), "Расшифровка лекции (оригинал)",
                                    "Расшифровка"));
                }
                case RUSSIAN -> {
                    LectureDocumentRevision transcript = ensure(lectureId, DocumentType.TRANSCRIPT);
                    LectureDocumentRevision translation = ensure(lectureId, DocumentType.TRANSLATION);
                    transcriptRevisionId = transcript == null ? null : transcript.id();
                    translationRevisionId = translation == null ? null : translation.id();
                    List<TranslationSegment> translated = translationSegments(lectureId);
                    if (translated.isEmpty()) {
                        throw EditorException.validation("Нет перевода для экспорта");
                    }
                    List<TranscriptSegment> visible = translated.stream()
                            .map(TranslationSegment::toTranscriptSegment).toList();
                    docxExporter.exportTranscript(temp, "Расшифровка (RU): " + title, visible,
                            slideImages, "Перевод: русский");
                    writeStringAtomic(storage.resolve(lectureId, variant.markdownArtifact()),
                            markdownBuilder.transcriptMarkdown(lecture, new Transcript(visible, ""),
                                    "Расшифровка лекции (перевод на русский)", "Перевод"));
                }
                case BILINGUAL -> {
                    LectureDocumentRevision transcript = ensure(lectureId, DocumentType.TRANSCRIPT);
                    LectureDocumentRevision translation = ensure(lectureId, DocumentType.TRANSLATION);
                    transcriptRevisionId = transcript == null ? null : transcript.id();
                    translationRevisionId = translation == null ? null : translation.id();
                    List<TranscriptSegment> original = transcriptSegments(lectureId);
                    Map<Integer, TranslationSegment> bySequence = new HashMap<>();
                    translationSegments(lectureId).forEach(s -> bySequence.put(s.sequence(), s));
                    List<DocxExporter.BilingualLine> lines = new ArrayList<>();
                    for (TranscriptSegment segment : original) {
                        TranslationSegment translated = bySequence.get(segment.sequence());
                        String ru = translated == null ? segment.text() : translated.toTranscriptSegment().text();
                        lines.add(new DocxExporter.BilingualLine(segment.startMs(), segment.endMs(),
                                segment.text(), ru));
                    }
                    if (lines.isEmpty()) {
                        throw EditorException.validation("Нет текста для двуязычного экспорта");
                    }
                    docxExporter.exportBilingual(temp, "Расшифровка (оригинал + перевод): " + title,
                            lines, slideImages);
                    writeStringAtomic(storage.resolve(lectureId, variant.markdownArtifact()),
                            markdownBuilder.bilingualTranscriptMarkdown(lecture,
                                    new Transcript(original, ""),
                                    translationSegments(lectureId)));
                }
                case NOTES -> {
                    LectureDocumentRevision notes = ensure(lectureId, DocumentType.NOTES);
                    notesRevisionId = notes == null ? null : notes.id();
                    String content = notes == null ? "" : notes.content();
                    if (content.isBlank()) {
                        throw EditorException.validation("Конспект пуст — нечего экспортировать");
                    }
                    docxExporter.exportMarkdown(temp, title, content, slideImages);
                    writeStringAtomic(storage.resolve(lectureId, variant.markdownArtifact()), content);
                }
            }
            Path markdownTarget = storage.resolve(lectureId, variant.markdownArtifact());
            if (variant == ExportVariant.ORIGINAL || variant == ExportVariant.RUSSIAN
                    || variant == ExportVariant.BILINGUAL) {
                // The Markdown file was written before the DOCX move so a failed
                // DOCX render never replaces the previous good document.
                log.debug("Markdown artifact written: {}", markdownTarget);
            }
            atomicMove(temp, target);
            long size = Files.size(target);
            String hash = editor.exportSourceHash(lectureId, variant);
            long id = exports.insert(lectureId, variant, variant.docxArtifact(), hash,
                    transcriptRevisionId, translationRevisionId, notesRevisionId, size,
                    "COMPLETED", null);
            if (variant == ExportVariant.NOTES) {
                lectureRepository.updateDocxPath(lectureId, target.toAbsolutePath().toString());
            }
            log.info("Lecture {} export {} completed ({} bytes, source hash {})",
                    lectureId, variant, size, hash);
            return exports.findById(lectureId, id).orElseThrow();
        } catch (EditorException e) {
            recordFailure(lectureId, variant, e.getMessage());
            throw e;
        } catch (Exception e) {
            recordFailure(lectureId, variant, e.getMessage());
            log.warn("Lecture {} export {} failed: {}", lectureId, variant, e.getMessage());
            throw EditorException.unavailable("Не удалось создать документ: " + e.getMessage());
        } finally {
            deleteQuietly(temp);
        }
    }

    private void recordFailure(String lectureId, ExportVariant variant, String message) {
        try {
            exports.insert(lectureId, variant, variant.docxArtifact(), null, null, null, null,
                    0L, "FAILED", message == null ? "unknown error"
                            : message.substring(0, Math.min(500, message.length())));
        } catch (RuntimeException e) {
            log.debug("Could not record failed export: {}", e.getMessage());
        }
    }

    private LectureDocumentRevision ensure(String lectureId, DocumentType type) {
        return revisions.ensureInitial(lectureId, type).orElse(null);
    }

    private List<TranscriptSegment> transcriptSegments(String lectureId) {
        return revisions.latest(lectureId, DocumentType.TRANSCRIPT)
                .map(revision -> codec.decodeTranscript(revision.content()))
                .orElse(List.of());
    }

    private List<TranslationSegment> translationSegments(String lectureId) {
        return revisions.latest(lectureId, DocumentType.TRANSLATION)
                .map(revision -> codec.decodeTranslation(revision.content()))
                .orElse(List.of());
    }

    /**
     * Uses only already extracted slides (no re-extraction). Missing files are
     * skipped so a deleted slide never breaks the whole export.
     */
    private List<SlideImage> existingSlides(String lectureId) {
        List<Slide> stored = slides.findByLecture(lectureId);
        List<SlideImage> result = new ArrayList<>();
        for (Slide slide : stored) {
            if (slide.imagePath() == null) {
                continue;
            }
            Path path = Path.of(slide.imagePath());
            if (!Files.isRegularFile(path)) {
                log.warn("Slide {} of lecture {} is missing on disk: {}", slide.sequence(),
                        lectureId, slide.imagePath());
                continue;
            }
            result.add(new SlideImage(slide.timestampMs(), path,
                    "Слайд [" + Transcript.formatTimestamp(slide.timestampMs()) + "]"));
        }
        return result;
    }

    private static void writeStringAtomic(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temp, content == null ? "" : content, StandardCharsets.UTF_8);
        atomicMove(temp, target);
    }

    private static void atomicMove(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
