package com.lectureprocessor.editor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.Slide;
import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.editor.EditorRequests.AcceptSuggestionRequest;
import com.lectureprocessor.editor.EditorRequests.NotesSave;
import com.lectureprocessor.editor.EditorRequests.SegmentEdit;
import com.lectureprocessor.editor.EditorRequests.TranscriptSave;
import com.lectureprocessor.editor.EditorRequests.TranslationEdit;
import com.lectureprocessor.editor.EditorRequests.TranslationSave;
import com.lectureprocessor.editor.EditorView.ExportState;
import com.lectureprocessor.editor.EditorView.QualitySummary;
import com.lectureprocessor.editor.EditorView.RevisionInfo;
import com.lectureprocessor.editor.EditorView.TranslationView;
import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.multilingual.EditorRetranslationException;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageService;
import com.lectureprocessor.multilingual.LectureTranslation;
import com.lectureprocessor.multilingual.RetranslationSuggestion;
import com.lectureprocessor.multilingual.SegmentRetranslationService;
import com.lectureprocessor.multilingual.SegmentTranslationStatus;
import com.lectureprocessor.multilingual.TranslationRepository;
import com.lectureprocessor.multilingual.TranslationSegment;
import com.lectureprocessor.multilingual.TranslationStatus;
import com.lectureprocessor.pipeline.PipelineDataLoader;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.repository.AppSettingRepository;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.SegmentRepository;
import com.lectureprocessor.repository.SlideRepository;
import com.lectureprocessor.repository.StageCheckpointRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.text.PromptTemplates;
import com.lectureprocessor.text.StructuredNotes;
import com.lectureprocessor.text.TextProcessingService;
import com.lectureprocessor.util.FileChecksum;

/**
 * Application service behind {@code /lectures/{id}/editor}.
 *
 * <p>Guarantees:</p>
 * <ul>
 *   <li>every save appends an immutable revision and checks optimistic locking;</li>
 *   <li>the raw Whisper artifact and the original transcript revision are never
 *       overwritten;</li>
 *   <li>editing the original marks dependent translations {@code STALE} instead
 *       of silently keeping outdated text;</li>
 *   <li>no editor operation downloads media, runs FFmpeg or Whisper;</li>
 *   <li>human corrections are never overwritten by automated regeneration.</li>
 * </ul>
 */
@Service
public class LectureEditorService {

    private static final Logger log = LoggerFactory.getLogger(LectureEditorService.class);
    static final String CLOUD_RETRY_SETTING = "editor.allow-cloud-retry";

    private final LectureService lectures;
    private final LectureRepository lectureRepository;
    private final SegmentRepository segments;
    private final TranslationRepository translations;
    private final SlideRepository slides;
    private final DocumentRevisionService revisions;
    private final DocumentStateRepository states;
    private final SegmentCorrectionRepository corrections;
    private final LectureExportRepository exports;
    private final SegmentRetranslationService retranslation;
    private final LectureLanguageService languages;
    private final TextProcessingService textProcessing;
    private final LlmTaskResolver taskResolver;
    private final PipelineDataLoader loader;
    private final FileStorageService storage;
    private final StageCheckpointRepository checkpoints;
    private final SafeMarkdownRenderer markdown;
    private final AppSettingRepository settings;
    private final EditorProperties properties;
    private final EditorContentCodec codec;

    public LectureEditorService(LectureService lectures, LectureRepository lectureRepository,
            SegmentRepository segments, TranslationRepository translations, SlideRepository slides,
            DocumentRevisionService revisions, DocumentStateRepository states,
            SegmentCorrectionRepository corrections, LectureExportRepository exports,
            SegmentRetranslationService retranslation, LectureLanguageService languages,
            TextProcessingService textProcessing, LlmTaskResolver taskResolver,
            PipelineDataLoader loader, FileStorageService storage,
            StageCheckpointRepository checkpoints, SafeMarkdownRenderer markdown,
            AppSettingRepository settings, EditorProperties properties,
            EditorContentCodec codec) {
        this.lectures = lectures;
        this.lectureRepository = lectureRepository;
        this.segments = segments;
        this.translations = translations;
        this.slides = slides;
        this.revisions = revisions;
        this.states = states;
        this.corrections = corrections;
        this.exports = exports;
        this.retranslation = retranslation;
        this.languages = languages;
        this.textProcessing = textProcessing;
        this.taskResolver = taskResolver;
        this.loader = loader;
        this.storage = storage;
        this.checkpoints = checkpoints;
        this.markdown = markdown;
        this.settings = settings;
        this.properties = properties;
        this.codec = codec;
    }

    // ------------------------------------------------------------------ view

    public EditorView view(String lectureId) {
        Lecture lecture = requireLecture(lectureId);
        LectureLanguage language = languages.get(lectureId);
        for (DocumentType type : DocumentType.values()) {
            revisions.syncMachineRevision(lectureId, type);
        }

        List<TranscriptSegment> transcript = segments.findByLecture(lectureId);
        LectureTranslation translation = translations.findLatest(lectureId).orElse(null);
        List<TranslationSegment> translationSegments = translation == null ? List.of()
                : translations.segments(translation.id());
        String notes = currentNotes(lectureId, lecture);
        Map<DocumentType, LectureDocumentState> stateMap = new HashMap<>();
        states.findByLecture(lectureId).forEach((type, state) -> stateMap.put(type, state));
        for (DocumentType type : DocumentType.values()) {
            stateMap.putIfAbsent(type, new LectureDocumentState(lectureId, type,
                    DocumentStatus.CURRENT, null, Instant.now()));
        }
        Map<DocumentType, RevisionInfo> revisionInfo = new LinkedHashMap<>();
        for (DocumentType type : DocumentType.values()) {
            revisions.latest(lectureId, type).ifPresent(revision -> revisionInfo.put(type,
                    new RevisionInfo(revision.revisionNumber(), revision.id(),
                            revision.editSource().name(), revision.createdAt().toString())));
        }
        List<Slide> slideList = slides.findByLecture(lectureId);
        QualitySummary quality = quality(transcriptSegments(translationSegments, transcript));
        return new EditorView(lecture, language.detectedLanguage(), language.normalizedSource(),
                language.resolvedTranslationMode().name(), language.targetLanguage(), transcript,
                new TranslationView(translation == null ? null : translation.id(),
                        translation == null ? null : translation.provider(),
                        translation == null ? null : translation.model(),
                        translation == null ? null : translation.sourceLanguage(),
                        translation == null ? null : translation.targetLanguage(),
                        translation == null ? null : translation.status().name(),
                        translationSegments),
                notes, stateMap, revisionInfo, slideList, quality,
                exportStates(lectureId), exports.findByLecture(lectureId, 25),
                allowCloudRetry(), cloudRetryConfigured());
    }

    private List<TranslationSegment> transcriptSegments(List<TranslationSegment> translationSegments,
            List<TranscriptSegment> transcript) {
        if (!translationSegments.isEmpty()) {
            return translationSegments;
        }
        return transcript.stream().map(TranslationSegment::pending).toList();
    }

    private QualitySummary quality(List<TranslationSegment> translationSegments) {
        int total = translationSegments.size();
        int warnings = (int) translationSegments.stream()
                .filter(s -> s.status() == SegmentTranslationStatus.WARNING).count();
        int corrected = (int) translationSegments.stream()
                .filter(s -> s.status() == SegmentTranslationStatus.MANUALLY_CORRECTED).count();
        int reviewed = (int) translationSegments.stream()
                .filter(s -> s.status() == SegmentTranslationStatus.REVIEWED).count();
        int stale = (int) translationSegments.stream()
                .filter(s -> s.status() == SegmentTranslationStatus.STALE).count();
        int translated = (int) translationSegments.stream()
                .filter(TranslationSegment::done).count();
        int pending = total - translated - stale;
        return new QualitySummary(total, translated, warnings, corrected, reviewed, stale,
                Math.max(0, pending));
    }

    private String currentNotes(String lectureId, Lecture lecture) {
        return revisions.latest(lectureId, DocumentType.NOTES)
                .map(LectureDocumentRevision::content)
                .orElseGet(() -> lecture.notesMarkdown() == null ? "" : lecture.notesMarkdown());
    }

    // --------------------------------------------------------- transcript save

    public LectureDocumentRevision saveTranscript(String lectureId, TranscriptSave request) {
        Lecture lecture = requireLecture(lectureId);
        if (request == null || request.edits() == null || request.edits().isEmpty()) {
            throw EditorException.validation("Нет изменений для сохранения");
        }
        if (request.edits().size() > properties.maxBatchSegments()) {
            throw EditorException.validation("Слишком много сегментов в одном запросе");
        }
        validateDescription(request.description());
        revisions.ensureInitial(lectureId, DocumentType.TRANSCRIPT);
        revisions.assertRevision(lectureId, DocumentType.TRANSCRIPT, nullableLong(request.expectedRevision()));

        List<TranscriptSegment> current = segments.findByLecture(lectureId);
        Map<Integer, TranscriptSegment> bySequence = new HashMap<>();
        current.forEach(segment -> bySequence.put(segment.sequence(), segment));
        List<Integer> changed = new ArrayList<>();
        for (SegmentEdit edit : request.edits()) {
            TranscriptSegment existing = bySequence.get(edit.sequence());
            if (existing == null) {
                throw EditorException.validation("Сегмент " + edit.sequence() + " не найден");
            }
            String text = validateSegmentText(edit.text());
            if (!text.equals(existing.text())) {
                segments.updateText(lectureId, edit.sequence(), text);
                TranscriptSegment updated = new TranscriptSegment(existing.sequence(),
                        existing.startMs(), existing.endMs(), text);
                bySequence.put(edit.sequence(), updated);
                changed.add(edit.sequence());
                corrections.upsert(lectureId, DocumentType.TRANSCRIPT, edit.sequence(), null,
                        existing.text(), text, SegmentReviewStatus.MANUALLY_CORRECTED,
                        request.description(), null, null);
            }
        }
        if (changed.isEmpty()) {
            throw EditorException.validation("Текст не изменился");
        }
        List<TranscriptSegment> ordered = current.stream()
                .map(segment -> bySequence.get(segment.sequence()))
                .toList();
        LectureDocumentRevision previous = revisions.latest(lectureId, DocumentType.TRANSCRIPT)
                .orElseThrow();
        LectureDocumentRevision revision = revisions.append(lectureId, DocumentType.TRANSCRIPT,
                codec.encodeTranscript(ordered), EditSource.HUMAN,
                descriptionOr(request.description(), "Ручная правка расшифровки"),
                previous.id());
        int staleSegments = markTranslationsStale(lectureId, changed, bySequence);
        states.markStale(lectureId, DocumentType.NOTES,
                "Изменена оригинальная расшифровка — конспект нужно пересоздать");
        if (translations.findLatest(lectureId).isPresent()) {
            states.markStale(lectureId, DocumentType.TRANSLATION,
                    "Оригинал изменён: " + staleSegments + " сегмент(ов) перевода устарели");
        }
        // Text processing + export must re-run; transcription (Whisper) is kept.
        checkpoints.invalidateFrom(lectureId, PipelineStage.TEXT_PROCESSING);
        log.info("Lecture {} transcript saved as revision {} ({} changed segments, {} stale translations)",
                lectureId, revision.revisionNumber(), changed.size(), staleSegments);
        return revision;
    }

    private int markTranslationsStale(String lectureId, List<Integer> changedSequences,
            Map<Integer, TranscriptSegment> updated) {
        Optional<LectureTranslation> latest = translations.findLatest(lectureId);
        if (latest.isEmpty()) {
            return 0;
        }
        List<TranslationSegment> stored = translations.segments(latest.get().id());
        List<TranslationSegment> working = new ArrayList<>(stored);
        int stale = 0;
        for (int i = 0; i < working.size(); i++) {
            TranslationSegment segment = working.get(i);
            if (!changedSequences.contains(segment.sequence())) {
                continue;
            }
            TranscriptSegment updatedSegment = updated.get(segment.sequence());
            if (updatedSegment == null || updatedSegment.text().equals(segment.sourceText())) {
                continue;
            }
            working.set(i, new TranslationSegment(segment.sequence(), segment.startMs(),
                    segment.endMs(), updatedSegment.text(), segment.translatedText(),
                    SegmentTranslationStatus.STALE, segment.quality(),
                    "оригинал изменён: перевод устарел", segment.error(), segment.attempts()));
            stale++;
        }
        if (stale > 0) {
            translations.upsertSegments(latest.get().id(), working);
            refreshTranslationCounters(latest.get(), working);
        }
        return stale;
    }

    // -------------------------------------------------------- translation save

    public LectureDocumentRevision saveTranslation(String lectureId, TranslationSave request) {
        Lecture lecture = requireLecture(lectureId);
        if (request == null || request.edits() == null || request.edits().isEmpty()) {
            throw EditorException.validation("Нет изменений для сохранения");
        }
        if (request.edits().size() > properties.maxBatchSegments()) {
            throw EditorException.validation("Слишком много сегментов в одном запросе");
        }
        validateDescription(request.description());
        revisions.ensureInitial(lectureId, DocumentType.TRANSLATION);
        revisions.assertRevision(lectureId, DocumentType.TRANSLATION,
                nullableLong(request.expectedRevision()));
        LectureTranslation translation = translations.findLatest(lectureId)
                .orElseThrow(() -> EditorException.validation(
                        "Для этой лекции ещё нет перевода — сначала запустите перевод"));
        List<TranslationSegment> working = new ArrayList<>(translations.segments(translation.id()));
        Map<Integer, TranslationSegment> bySequence = new HashMap<>();
        working.forEach(segment -> bySequence.put(segment.sequence(), segment));
        boolean human = false;
        for (TranslationEdit edit : request.edits()) {
            TranslationSegment existing = bySequence.get(edit.sequence());
            if (existing == null) {
                throw EditorException.validation("Сегмент перевода " + edit.sequence() + " не найден");
            }
            String text = validateSegmentText(edit.text());
            boolean aiAssisted = Boolean.TRUE.equals(edit.aiAssisted());
            human = human || !aiAssisted;
            TranslationSegment updated = aiAssisted
                    ? new TranslationSegment(existing.sequence(), existing.startMs(), existing.endMs(),
                            existing.sourceText(), text, SegmentTranslationStatus.MANUALLY_CORRECTED,
                            "OK", null, null, existing.attempts() + 1)
                    : existing.corrected(text);
            bySequence.put(edit.sequence(), updated);
            corrections.upsert(lectureId, DocumentType.TRANSLATION, edit.sequence(),
                    translation.id(), existing.translatedText(), text,
                    SegmentReviewStatus.MANUALLY_CORRECTED, edit.note(),
                    edit.provider(), edit.model());
        }
        List<TranslationSegment> ordered = working.stream()
                .map(segment -> bySequence.get(segment.sequence()))
                .toList();
        translations.upsertSegments(translation.id(), ordered);
        refreshTranslationCounters(translation, ordered);
        LectureDocumentRevision previous = revisions.latest(lectureId, DocumentType.TRANSLATION)
                .orElseThrow();
        LectureDocumentRevision revision = revisions.append(lectureId, DocumentType.TRANSLATION,
                codec.encodeTranslation(ordered),
                human ? EditSource.HUMAN : EditSource.AI_ASSISTED,
                descriptionOr(request.description(), human
                        ? "Ручная правка перевода" : "Принят вариант повторного перевода"),
                previous.id());
        states.markCurrent(lectureId, DocumentType.TRANSLATION);
        states.markStale(lectureId, DocumentType.NOTES,
                "Перевод изменён — конспект нужно пересоздать");
        checkpoints.invalidateFrom(lectureId, PipelineStage.TEXT_PROCESSING);
        log.info("Lecture {} translation saved as revision {} ({} segments)",
                lectureId, revision.revisionNumber(), ordered.size());
        return revision;
    }

    public void markReviewed(String lectureId, int sequence) {
        requireLecture(lectureId);
        LectureTranslation translation = translations.findLatest(lectureId)
                .orElseThrow(() -> EditorException.notFound("Перевод не найден"));
        List<TranslationSegment> working = new ArrayList<>(translations.segments(translation.id()));
        boolean found = false;
        for (int i = 0; i < working.size(); i++) {
            TranslationSegment segment = working.get(i);
            if (segment.sequence() != sequence) {
                continue;
            }
            found = true;
            working.set(i, segment.reviewed());
            corrections.upsert(lectureId, DocumentType.TRANSLATION, sequence, translation.id(),
                    segment.translatedText(), segment.translatedText(),
                    SegmentReviewStatus.REVIEWED, "проверено вручную",
                    translation.provider(), translation.model());
        }
        if (!found) {
            throw EditorException.notFound("Сегмент " + sequence + " не найден");
        }
        translations.upsertSegments(translation.id(), working);
        refreshTranslationCounters(translation, working);
    }

    // --------------------------------------------------------------- retry AI

    public EditorView.RetranslationPreview previewRetranslation(String lectureId, int sequence) {
        requireLecture(lectureId);
        ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.LECTURE_TRANSLATION);
        if (resolved.cloud() && !allowCloudRetry()) {
            throw EditorException.forbidden(
                    "Повторный перевод через облако отключён. Включите «Allow cloud retry "
                            + "for translation warnings» в настройках редактора — иначе приватный "
                            + "текст лекции не должен покидать локальную установку.");
        }
        try {
            RetranslationSuggestion suggestion = retranslation.suggest(lectureId, sequence);
            boolean humanProtected = translations.findLatest(lectureId)
                    .map(t -> translations.segments(t.id()).stream()
                            .filter(s -> s.sequence() == sequence).findFirst()
                            .map(TranslationSegment::humanOwned).orElse(false))
                    .orElse(false);
            int expected = revisions.latest(lectureId, DocumentType.TRANSLATION)
                    .map(LectureDocumentRevision::revisionNumber).orElse(0);
            return new EditorView.RetranslationPreview(suggestion, humanProtected, expected);
        } catch (EditorRetranslationException e) {
            throw e.isNotFound() ? EditorException.notFound(e.getMessage())
                    : EditorException.unavailable(e.getMessage());
        }
    }

    public LectureDocumentRevision acceptRetranslation(String lectureId,
            AcceptSuggestionRequest request) {
        if (request == null || request.sequence() == null || request.text() == null) {
            throw EditorException.validation("Не передан вариант перевода");
        }
        TranslationSave save = new TranslationSave(request.expectedRevision(),
                List.of(new TranslationEdit(request.sequence(), request.text(), request.provider(),
                        request.model(), true, "принят повторный перевод")),
                descriptionOr(request.description(), "Принят повторный перевод сегмента"));
        return saveTranslation(lectureId, save);
    }

    // --------------------------------------------------------------- notes

    public LectureDocumentRevision saveNotes(String lectureId, NotesSave request) {
        Lecture lecture = requireLecture(lectureId);
        if (request == null || request.markdown() == null) {
            throw EditorException.validation("Не передан текст конспекта");
        }
        validateNotes(request.markdown());
        validateDescription(request.description());
        revisions.ensureInitial(lectureId, DocumentType.NOTES);
        revisions.assertRevision(lectureId, DocumentType.NOTES, nullableLong(request.expectedRevision()));
        Optional<LectureDocumentRevision> previous = revisions.latest(lectureId, DocumentType.NOTES);
        LectureDocumentRevision revision = revisions.append(lectureId, DocumentType.NOTES,
                request.markdown(), EditSource.HUMAN,
                descriptionOr(request.description(), "Ручная правка конспекта"),
                previous.map(LectureDocumentRevision::id).orElse(null));
        lectureRepository.updateNotesMarkdown(lectureId, request.markdown());
        loader.writeString(lectureId, PipelineDataLoader.NOTES_MD, request.markdown());
        loader.writeNotes(lectureId, new StructuredNotes(request.markdown(), null, List.of()));
        states.markCurrent(lectureId, DocumentType.NOTES);
        checkpoints.invalidateFrom(lectureId, PipelineStage.EXPORT);
        log.info("Lecture {} notes saved as revision {}", lectureId, revision.revisionNumber());
        return revision;
    }

    /**
     * Regenerates notes from the latest translation (or the original transcript)
     * using only the text-processing LLM. It never runs download/FFmpeg/Whisper
     * and never overwrites the human revision silently — the result is appended
     * as a new {@code AI_ASSISTED} revision which the user can compare/restore.
     */
    public LectureDocumentRevision regenerateNotes(String lectureId) {
        Lecture lecture = requireLecture(lectureId);
        ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.LECTURE_PROCESSING);
        if (!resolved.provider().isAvailable()) {
            throw EditorException.unavailable("LLM provider недоступен: " + resolved.providerId());
        }
        List<TranscriptSegment> source = noteSourceSegments(lectureId);
        if (source.isEmpty()) {
            throw EditorException.validation("Нет текста для генерации конспекта");
        }
        String promptTemplate = resolved.config().promptTemplate() == null
                ? PromptTemplates.STRUCTURED_NOTES : resolved.config().promptTemplate();
        StructuredNotes notes;
        try {
            notes = textProcessing.generateStructuredNotes(resolved.provider(), source,
                    resolved.options(), promptTemplate);
        } catch (RuntimeException e) {
            throw EditorException.unavailable("Не удалось пересоздать конспект: " + e.getMessage());
        }
        loader.writeNotes(lectureId, notes);
        loader.writeString(lectureId, PipelineDataLoader.NOTES_MD, notes.markdown());
        lectureRepository.updateNotesMarkdown(lectureId, notes.markdown());
        Optional<LectureDocumentRevision> previous = revisions.latest(lectureId, DocumentType.NOTES);
        LectureDocumentRevision revision = revisions.append(lectureId, DocumentType.NOTES,
                notes.markdown(), EditSource.AI_ASSISTED,
                "Конспект пересоздан LLM (" + resolved.providerId() + " / " + resolved.model() + ")",
                previous.map(LectureDocumentRevision::id).orElse(null));
        states.markCurrent(lectureId, DocumentType.NOTES);
        checkpoints.invalidateFrom(lectureId, PipelineStage.EXPORT);
        log.info("Lecture {} notes regenerated as revision {}", lectureId, revision.revisionNumber());
        return revision;
    }

    private List<TranscriptSegment> noteSourceSegments(String lectureId) {
        LectureLanguage language = languages.get(lectureId);
        if (language.resolvedTranslationMode().wantsTranslation()) {
            Optional<LectureTranslation> translation = translations.findLatest(lectureId);
            if (translation.isPresent()) {
                List<TranslationSegment> parts = translations.segments(translation.get().id());
                List<TranscriptSegment> used = parts.stream()
                        .filter(TranslationSegment::done)
                        .map(TranslationSegment::toTranscriptSegment)
                        .toList();
                if (!used.isEmpty()) {
                    return used;
                }
            }
        }
        return segments.findByLecture(lectureId);
    }

    // ------------------------------------------------------------- restore

    public LectureDocumentRevision restore(String lectureId, DocumentType type, long revisionId) {
        Lecture lecture = requireLecture(lectureId);
        LectureDocumentRevision target = revisions.find(lectureId, type, revisionId)
                .orElseThrow(() -> EditorException.notFound("Версия не найдена"));
        LectureDocumentRevision latest = revisions.latest(lectureId, type).orElse(null);
        return switch (type) {
            case TRANSCRIPT -> restoreTranscript(lectureId, lecture, target);
            case TRANSLATION -> restoreTranslation(lectureId, lecture, target);
            case NOTES -> restoreNotes(lectureId, lecture, target, latest);
        };
    }

    private LectureDocumentRevision restoreTranscript(String lectureId, Lecture lecture,
            LectureDocumentRevision target) {
        List<TranscriptSegment> restored = codec.decodeTranscript(target.content());
        if (restored.isEmpty()) {
            throw EditorException.validation("Восстанавливаемая версия пуста");
        }
        List<TranscriptSegment> current = segments.findByLecture(lectureId);
        Map<Integer, TranscriptSegment> before = new HashMap<>();
        current.forEach(segment -> before.put(segment.sequence(), segment));
        List<Integer> changed = new ArrayList<>();
        for (TranscriptSegment segment : restored) {
            TranscriptSegment existing = before.get(segment.sequence());
            if (existing != null && !existing.text().equals(segment.text())) {
                changed.add(segment.sequence());
            }
        }
        segments.replaceAll(lectureId, restored);
        LectureDocumentRevision revision = revisions.append(lectureId, DocumentType.TRANSCRIPT,
                codec.encodeTranscript(restored), EditSource.HUMAN,
                "Восстановлена версия #" + target.revisionNumber(), target.id());
        markTranslationsStale(lectureId, changed, restored.stream()
                .collect(java.util.stream.Collectors.toMap(TranscriptSegment::sequence,
                        segment -> segment, (a, b) -> b)));
        states.markStale(lectureId, DocumentType.NOTES,
                "Восстановлена предыдущая расшифровка — конспект может быть устаревшим");
        checkpoints.invalidateFrom(lectureId, PipelineStage.TEXT_PROCESSING);
        return revision;
    }

    private LectureDocumentRevision restoreTranslation(String lectureId, Lecture lecture,
            LectureDocumentRevision target) {
        LectureTranslation translation = translations.findLatest(lectureId)
                .orElseThrow(() -> EditorException.notFound("Перевод не найден"));
        List<TranslationSegment> restored = codec.decodeTranslation(target.content());
        if (restored.isEmpty()) {
            throw EditorException.validation("Восстанавливаемая версия пуста");
        }
        translations.upsertSegments(translation.id(), restored);
        refreshTranslationCounters(translation, restored);
        LectureDocumentRevision revision = revisions.append(lectureId, DocumentType.TRANSLATION,
                codec.encodeTranslation(restored), EditSource.HUMAN,
                "Восстановлена версия #" + target.revisionNumber(), target.id());
        states.markCurrent(lectureId, DocumentType.TRANSLATION);
        states.markStale(lectureId, DocumentType.NOTES,
                "Восстановлен предыдущий перевод — конспект может быть устаревшим");
        checkpoints.invalidateFrom(lectureId, PipelineStage.TEXT_PROCESSING);
        return revision;
    }

    private LectureDocumentRevision restoreNotes(String lectureId, Lecture lecture,
            LectureDocumentRevision target, LectureDocumentRevision latest) {
        validateNotes(target.content());
        lectureRepository.updateNotesMarkdown(lectureId, target.content());
        loader.writeString(lectureId, PipelineDataLoader.NOTES_MD, target.content());
        loader.writeNotes(lectureId, new StructuredNotes(target.content(), null, List.of()));
        LectureDocumentRevision revision = revisions.append(lectureId, DocumentType.NOTES,
                target.content(), EditSource.HUMAN,
                "Восстановлена версия #" + target.revisionNumber(), target.id());
        states.markCurrent(lectureId, DocumentType.NOTES);
        checkpoints.invalidateFrom(lectureId, PipelineStage.EXPORT);
        return revision;
    }

    // ------------------------------------------------------------- history

    /** Latest revision number per document (0 when no revision exists yet). */
    public int latestRevisionNumber(String lectureId, DocumentType type) {
        return revisions.latest(lectureId, type)
                .map(LectureDocumentRevision::revisionNumber).orElse(0);
    }

    public List<LectureDocumentRevision> history(String lectureId, DocumentType type, int limit) {
        requireLecture(lectureId);
        revisions.ensureInitial(lectureId, type);
        return revisions.history(lectureId, type, limit);
    }

    /** Explicit cleanup of old revisions; never runs implicitly on save. */
    public int pruneRevisions(String lectureId, DocumentType type, int keep) {
        requireLecture(lectureId);
        if (keep < 2) {
            throw EditorException.validation("Нужно сохранить минимум 2 ревизии "
                    + "(включая исходную)");
        }
        return revisions.prune(lectureId, type, keep);
    }

    public List<LineDiff.Line> compare(String lectureId, DocumentType type, long fromId, long toId) {
        requireLecture(lectureId);
        LectureDocumentRevision from = revisions.find(lectureId, type, fromId)
                .orElseThrow(() -> EditorException.notFound("Версия не найдена"));
        LectureDocumentRevision to = revisions.find(lectureId, type, toId)
                .orElseThrow(() -> EditorException.notFound("Версия не найдена"));
        return LineDiff.compare(from.content(), to.content());
    }

    // ------------------------------------------------------------- markdown

    public String renderMarkdown(String lectureId, String source) {
        requireLecture(lectureId);
        if (source != null && source.length() > properties.maxNotesChars()) {
            throw EditorException.validation("Слишком большой документ для предпросмотра");
        }
        return markdown.render(source, properties.previewMaxChars());
    }

    // ------------------------------------------------------------- settings

    public boolean cloudRetryConfigured() {
        return properties.allowCloudRetry();
    }

    public boolean allowCloudRetry() {
        if (!properties.allowCloudRetry()) {
            return false;
        }
        return settings.get(CLOUD_RETRY_SETTING).map(Boolean::parseBoolean).orElse(false);
    }

    public void setAllowCloudRetry(boolean enabled) {
        settings.put(CLOUD_RETRY_SETTING, Boolean.toString(enabled));
    }

    // --------------------------------------------------------------- helpers

    public List<ExportState> exportStates(String lectureId) {
        List<ExportState> result = new ArrayList<>();
        for (ExportVariant variant : ExportVariant.values()) {
            String currentHash = exportSourceHash(lectureId, variant);
            Optional<LectureExport> last = exports.latest(lectureId, variant);
            boolean available = last.isPresent() && "COMPLETED".equals(last.get().status())
                    && Files.isRegularFile(storage.resolve(lectureId, last.get().artifact()));
            boolean stale = available && currentHash != null
                    && !currentHash.equals(last.get().contentHash());
            String reason = stale ? "документ изменён после последнего экспорта" : null;
            result.add(new ExportState(variant, variant.docxArtifact(),
                    variant.markdownArtifact(), available, stale,
                    last.map(LectureExport::id).orElse(null),
                    last.map(export -> export.createdAt().toString()).orElse(null), reason));
        }
        return result;
    }

    /** Hash of the exact revisions an export variant is built from. */
    public String exportSourceHash(String lectureId, ExportVariant variant) {
        String transcript = revisions.latest(lectureId, DocumentType.TRANSCRIPT)
                .map(LectureDocumentRevision::contentHash).orElse("");
        String translation = revisions.latest(lectureId, DocumentType.TRANSLATION)
                .map(LectureDocumentRevision::contentHash).orElse("");
        String notes = revisions.latest(lectureId, DocumentType.NOTES)
                .map(LectureDocumentRevision::contentHash).orElse("");
        return switch (variant) {
            case ORIGINAL -> FileChecksum.sha256("original|" + transcript).substring(0, 32);
            case RUSSIAN -> FileChecksum.sha256("russian|" + transcript + "|" + translation)
                    .substring(0, 32);
            case BILINGUAL -> FileChecksum.sha256("bilingual|" + transcript + "|" + translation)
                    .substring(0, 32);
            case NOTES -> FileChecksum.sha256("notes|" + notes).substring(0, 32);
        };
    }

    private void refreshTranslationCounters(LectureTranslation translation,
            List<TranslationSegment> working) {
        int translated = (int) working.stream().filter(TranslationSegment::done).count();
        int failed = working.size() - translated
                - (int) working.stream()
                        .filter(s -> s.status() == SegmentTranslationStatus.STALE).count();
        TranslationStatus status = failed <= 0 ? TranslationStatus.COMPLETED
                : translated > 0 ? TranslationStatus.PARTIAL : TranslationStatus.FAILED;
        LectureTranslation updated = new LectureTranslation(translation.id(),
                translation.lectureId(), translation.sourceFingerprint(),
                translation.sourceLanguage(), translation.targetLanguage(), translation.provider(),
                translation.model(), translation.promptVersion(), translation.glossaryVersion(),
                translation.settingsHash(), status, working.size(), translated,
                Math.max(0, failed), translation.warnings(), translation.error(),
                translation.inputTokens(), translation.outputTokens(), translation.llmCalls(),
                translation.estimatedCost(), translation.costCurrency(),
                translation.createdAt(), Instant.now());
        translations.updateStatus(updated);
    }

    private Lecture requireLecture(String lectureId) {
        return lectures.getRequired(lectureId);
    }

    private static Long nullableLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    private void validateDescription(String description) {
        if (description != null && description.length() > properties.maxDescriptionChars()) {
            throw EditorException.validation("Описание правки слишком длинное");
        }
    }

    private String validateSegmentText(String text) {
        if (text == null) {
            throw EditorException.validation("Пустой текст сегмента");
        }
        String normalized = text.strip();
        if (normalized.isEmpty()) {
            throw EditorException.validation("Текст сегмента не может быть пустым");
        }
        if (normalized.length() > properties.maxSegmentChars()) {
            throw EditorException.validation("Текст сегмента слишком длинный (максимум "
                    + properties.maxSegmentChars() + " символов)");
        }
        if (normalized.indexOf('\0') >= 0) {
            throw EditorException.validation("Текст содержит недопустимые символы");
        }
        return normalized;
    }

    private void validateNotes(String markdown) {
        if (markdown == null) {
            throw EditorException.validation("Не передан текст конспекта");
        }
        if (markdown.length() > properties.maxNotesChars()) {
            throw EditorException.validation("Конспект слишком большой (максимум "
                    + properties.maxNotesChars() + " символов)");
        }
        if (markdown.indexOf('\0') >= 0) {
            throw EditorException.validation("Конспект содержит недопустимые символы");
        }
    }

    private static String descriptionOr(String description, String fallback) {
        return description == null || description.isBlank() ? fallback : description.strip();
    }
}
