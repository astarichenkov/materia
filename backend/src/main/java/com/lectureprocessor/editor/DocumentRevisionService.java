package com.lectureprocessor.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.multilingual.LectureTranslation;
import com.lectureprocessor.multilingual.TranslationRepository;
import com.lectureprocessor.multilingual.TranslationSegment;
import com.lectureprocessor.pipeline.PipelineDataLoader;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.SegmentRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.util.FileChecksum;

/**
 * Owns the revision streams and the optimistic-locking contract.
 *
 * <p>The first revision of each document is created lazily from existing
 * pipeline output ({@code WHISPER} / {@code TRANSLATION_LLM} /
 * {@code TEXT_PROCESSING_LLM}). Every later write appends a new immutable
 * revision; the original is never deleted by retention pruning.</p>
 */
@Service
public class DocumentRevisionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentRevisionService.class);

    private final DocumentRevisionRepository revisions;
    private final EditorContentCodec codec;
    private final SegmentRepository segments;
    private final TranslationRepository translations;
    private final LectureRepository lectures;
    private final PipelineDataLoader loader;
    private final FileStorageService storage;
    private final EditorProperties properties;

    public DocumentRevisionService(DocumentRevisionRepository revisions, EditorContentCodec codec,
            SegmentRepository segments, TranslationRepository translations,
            LectureRepository lectures, PipelineDataLoader loader, FileStorageService storage,
            EditorProperties properties) {
        this.revisions = revisions;
        this.codec = codec;
        this.segments = segments;
        this.translations = translations;
        this.lectures = lectures;
        this.loader = loader;
        this.storage = storage;
        this.properties = properties;
    }

    /**
     * Keeps a machine-produced revision in sync with the pipeline output. Called
     * before reading the editor view so a re-processed lecture shows the new
     * transcript/translation/notes. Human and AI-assisted revisions are never
     * replaced: they are deliberate user decisions.
     */
    public void syncMachineRevision(String lectureId, DocumentType type) {
        Optional<LectureDocumentRevision> latest = revisions.latest(lectureId, type);
        if (latest.isEmpty()) {
            ensureInitial(lectureId, type);
            return;
        }
        if (latest.get().editSource() == EditSource.HUMAN
                || latest.get().editSource() == EditSource.AI_ASSISTED) {
            return;
        }
        String current = currentContent(lectureId, type);
        if (current == null) {
            return;
        }
        String hash = FileChecksum.sha256(current);
        if (hash.equals(latest.get().contentHash())) {
            return;
        }
        EditSource source = switch (type) {
            case TRANSCRIPT -> EditSource.WHISPER;
            case TRANSLATION -> EditSource.TRANSLATION_LLM;
            case NOTES -> EditSource.TEXT_PROCESSING_LLM;
        };
        append(lectureId, type, current, source, "Обновлено конвейером обработки", latest.get().id());
    }

    private String currentContent(String lectureId, DocumentType type) {
        return switch (type) {
            case TRANSCRIPT -> {
                List<TranscriptSegment> values = segments.findByLecture(lectureId);
                yield values.isEmpty() ? null : codec.encodeTranscript(values);
            }
            case TRANSLATION -> translations.findLatest(lectureId)
                    .map(translation -> translations.segments(translation.id()))
                    .filter(values -> !values.isEmpty())
                    .map(codec::encodeTranslation)
                    .orElse(null);
            case NOTES -> {
                Lecture lecture = lectures.findById(lectureId).orElse(null);
                String markdown = lecture == null ? null : lecture.notesMarkdown();
                yield markdown == null || markdown.isBlank() ? null : markdown;
            }
        };
    }

    /** Creates the initial revision from pipeline artifacts when none exists. */
    public Optional<LectureDocumentRevision> ensureInitial(String lectureId, DocumentType type) {
        Optional<LectureDocumentRevision> existing = revisions.latest(lectureId, type);
        if (existing.isPresent()) {
            return existing;
        }
        Lecture lecture = lectures.findById(lectureId).orElse(null);
        if (lecture == null) {
            return Optional.empty();
        }
        return switch (type) {
            case TRANSCRIPT -> initialTranscript(lectureId);
            case TRANSLATION -> initialTranslation(lectureId);
            case NOTES -> initialNotes(lectureId, lecture);
        };
    }

    private Optional<LectureDocumentRevision> initialTranscript(String lectureId) {
        List<TranscriptSegment> source = segments.findByLecture(lectureId);
        if (source.isEmpty()) {
            var transcript = loader.transcript(lectureId);
            if (transcript == null || transcript.isEmpty()) {
                return Optional.empty();
            }
            source = transcript.segments();
        }
        return Optional.of(append(lectureId, DocumentType.TRANSCRIPT, codec.encodeTranscript(source),
                EditSource.WHISPER, "Исходная расшифровка", null));
    }

    private Optional<LectureDocumentRevision> initialTranslation(String lectureId) {
        LectureTranslation translation = translations.findLatest(lectureId).orElse(null);
        if (translation == null) {
            return Optional.empty();
        }
        List<TranslationSegment> source = translations.segments(translation.id());
        if (source.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(append(lectureId, DocumentType.TRANSLATION,
                codec.encodeTranslation(source), EditSource.TRANSLATION_LLM,
                "Исходный перевод (" + translation.provider() + " / " + translation.model() + ")",
                null));
    }

    private Optional<LectureDocumentRevision> initialNotes(String lectureId, Lecture lecture) {
        String markdown = lecture.notesMarkdown();
        if (markdown == null || markdown.isBlank()) {
            markdown = readIfExists(storage.resolve(lectureId, PipelineDataLoader.NOTES_MD));
        }
        var notes = loader.notes(lectureId);
        if ((markdown == null || markdown.isBlank()) && notes != null) {
            markdown = notes.markdown();
        }
        if (markdown == null || markdown.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(append(lectureId, DocumentType.NOTES, markdown,
                EditSource.TEXT_PROCESSING_LLM, "Исходный конспект", null));
    }

    /**
     * Appends a revision. The caller is responsible for applying the content to
     * the working tables; this method stores the immutable copy.
     */
    public LectureDocumentRevision append(String lectureId, DocumentType type, String content,
            EditSource source, String description, Long parentId) {
        int number = revisions.nextRevisionNumber(lectureId, type);
        String hash = FileChecksum.sha256(content == null ? "" : content);
        LectureDocumentRevision revision = revisions.insert(lectureId, type, number,
                content == null ? "" : content, hash, parentId, source, description);
        int removed = revisions.prune(lectureId, type, properties.maxRevisionsPerDocument());
        if (removed > 0) {
            log.info("Pruned {} old {} revision(s) for lecture {}", removed, type, lectureId);
        }
        return revision;
    }

    public Optional<LectureDocumentRevision> latest(String lectureId, DocumentType type) {
        return revisions.latest(lectureId, type);
    }

    public Optional<LectureDocumentRevision> find(String lectureId, DocumentType type, long id) {
        return revisions.findById(lectureId, type, id);
    }

    public List<LectureDocumentRevision> history(String lectureId, DocumentType type, int limit) {
        return revisions.history(lectureId, type, limit);
    }

    /**
     * Enforces optimistic locking: the caller must have loaded the latest
     * revision. A {@code null} expectation means "no prior revision" (first save).
     */
    public void assertRevision(String lectureId, DocumentType type, Long expectedRevision) {
        Optional<LectureDocumentRevision> latest = revisions.latest(lectureId, type);
        if (expectedRevision == null) {
            return;
        }
        int actual = latest.map(LectureDocumentRevision::revisionNumber).orElse(0);
        if (actual != expectedRevision) {
            throw EditorException.conflict("Документ был изменён в другой вкладке (текущая версия "
                    + actual + ", ожидалась " + expectedRevision
                    + "). Обновите страницу, чтобы не потерять изменения.");
        }
    }

    public void deleteByLecture(String lectureId) {
        revisions.deleteByLecture(lectureId);
    }

    private static String readIfExists(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
