package com.lectureprocessor.processing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.editor.DocumentRevisionRepository;
import com.lectureprocessor.editor.DocumentType;
import com.lectureprocessor.editor.EditSource;
import com.lectureprocessor.editor.LectureDocumentRevision;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageRepository;
import com.lectureprocessor.multilingual.MultilingualProperties;
import com.lectureprocessor.multilingual.TranslationRepository;
import com.lectureprocessor.multilingual.TranslationStatus;
import com.lectureprocessor.pipeline.JobState;
import com.lectureprocessor.pipeline.PipelineJob;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.pipeline.StageCheckpoint;
import com.lectureprocessor.pipeline.StageState;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.repository.StageCheckpointRepository;
import com.lectureprocessor.storage.FileStorageService;

/**
 * Builds a {@link RerunPlan} without starting anything.
 *
 * <p>The plan is intentionally explicit about WHY each stage runs or is reused.
 * It is derived from the persisted checkpoints and artifacts — not from the
 * requested profile alone — so the UI cannot promise "Whisper will not run"
 * when the transcript is actually missing.</p>
 */
@Service
public class RerunPlanService {

    private final ProcessingProfileService profiles;
    private final OutputVariantService variants;
    private final StageCheckpointRepository checkpoints;
    private final PipelineJobRepository jobs;
    private final LectureRepository lectures;
    private final TranslationRepository translations;
    private final LectureLanguageRepository languages;
    private final MultilingualProperties multilingual;
    private final DocumentRevisionRepository revisions;
    private final FileStorageService storage;

    public RerunPlanService(ProcessingProfileService profiles, OutputVariantService variants,
            StageCheckpointRepository checkpoints, PipelineJobRepository jobs,
            LectureRepository lectures, TranslationRepository translations,
            LectureLanguageRepository languages, MultilingualProperties multilingual,
            DocumentRevisionRepository revisions, FileStorageService storage) {
        this.profiles = profiles;
        this.variants = variants;
        this.checkpoints = checkpoints;
        this.jobs = jobs;
        this.lectures = lectures;
        this.translations = translations;
        this.languages = languages;
        this.multilingual = multilingual;
        this.revisions = revisions;
        this.storage = storage;
    }

    public RerunPlan plan(String lectureId, ProcessingProfile requested) {
        Lecture lecture = lectures.findById(lectureId).orElseThrow(
                () -> new com.lectureprocessor.lecture.LectureNotFoundException(lectureId));
        ProcessingProfile applied = appliedProfile(lectureId);
        Map<PipelineStage, StageCheckpoint> byStage = checkpoints.findByLecture(lectureId).stream()
                .collect(Collectors.toMap(StageCheckpoint::stage, Function.identity(), (a, b) -> a));

        PipelineJob job = jobs.find(lectureId).orElse(null);
        boolean activeJob = job != null && (job.state() == JobState.QUEUED
                || job.state() == JobState.RUNNING);
        String activeReason = activeJob
                ? "Задача уже выполняется: дождитесь завершения или отмените её." : null;

        boolean profileChanged = !requested.fingerprintPart().equals(applied.fingerprintPart());
        LectureLanguage language = languages.findOrDefault(lectureId, multilingual);
        boolean wantsTranslation = language.resolvedTranslationMode().wantsTranslation();
        boolean translationUsable = translations.findLatest(lectureId)
                .filter(t -> t.status() == TranslationStatus.COMPLETED
                        || t.status() == TranslationStatus.PARTIAL)
                .filter(t -> t.segmentsTranslated() > 0)
                .isPresent();

        List<RerunPlan.StagePlan> stages = new ArrayList<>();
        List<String> reused = new ArrayList<>();
        List<String> produced = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (PipelineStage stage : PipelineStage.values()) {
            StageCheckpoint checkpoint = byStage.get(stage);
            boolean completed = checkpoint != null && checkpoint.state() == StageState.COMPLETED;
            RerunPlan.Decision decision;
            String reason;
            List<String> artifacts = List.of();
            switch (stage) {
                case SOURCE_RESOLUTION -> {
                    decision = completed ? RerunPlan.Decision.REUSE : RerunPlan.Decision.RERUN;
                    reason = completed ? "Источник уже определён" : "Источник не разрешён";
                    artifacts = List.of("источник");
                }
                case DOWNLOADING -> {
                    boolean local = lecture.storedFilename() != null && Files.isRegularFile(
                            storage.resolve(lectureId, lecture.storedFilename()));
                    if (local || completed) {
                        decision = RerunPlan.Decision.REUSE;
                        reason = "Видео уже скачано или загружено локально";
                        artifacts = List.of("медиафайл");
                    } else {
                        decision = RerunPlan.Decision.RERUN;
                        reason = "Медиафайл отсутствует — потребуется повторное скачивание";
                    }
                }
                case MEDIA_VALIDATION -> {
                    decision = completed ? RerunPlan.Decision.REUSE : RerunPlan.Decision.RERUN;
                    reason = completed ? "Медиафайл уже проверен"
                            : "Медиафайл ещё не проверялся";
                    artifacts = completed ? List.of("метаданные медиа") : List.of();
                }
                case AUDIO_EXTRACTION -> {
                    boolean audio = Files.isRegularFile(storage.resolve(lectureId, "audio.wav"));
                    decision = completed && audio ? RerunPlan.Decision.REUSE
                            : RerunPlan.Decision.RERUN;
                    reason = completed && audio ? "Аудио уже извлечено"
                            : "Аудио отсутствует или не подготовлено";
                    artifacts = completed && audio ? List.of("audio.wav") : List.of();
                }
                case TRANSCRIPTION -> {
                    boolean transcript = Files.isRegularFile(
                            storage.resolve(lectureId, "transcript.json"));
                    decision = completed && transcript ? RerunPlan.Decision.REUSE
                            : RerunPlan.Decision.RERUN;
                    reason = completed && transcript
                            ? "Транскрипция уже готова — Whisper не запускается"
                            : "Транскрипция отсутствует — потребуется Whisper";
                    artifacts = completed && transcript ? List.of("transcript.json") : List.of();
                }
                case TRANSLATION -> {
                    if (!wantsTranslation) {
                        decision = RerunPlan.Decision.SKIP;
                        reason = "Перевод отключён в настройках языка";
                    } else if (translationUsable) {
                        decision = RerunPlan.Decision.REUSE;
                        reason = "Перевод уже готов и не менялся";
                        var translation = translations.findLatest(lectureId).orElse(null);
                        artifacts = translation == null ? List.of()
                                : List.of("перевод " + translation.segmentsTranslated() + "/"
                                        + translation.segmentsTotal() + " сегментов");
                    } else if (completed) {
                        decision = RerunPlan.Decision.REUSE;
                        reason = "Перевод отмечен выполненным";
                    } else {
                        decision = RerunPlan.Decision.RERUN;
                        reason = "Перевод ещё не выполнен или неполный";
                    }
                }
                case TEXT_PROCESSING -> {
                    boolean notes = Files.isRegularFile(
                            storage.resolve(lectureId, "notes.json"));
                    if (completed && notes && !profileChanged) {
                        decision = RerunPlan.Decision.REUSE;
                        reason = "Конспект соответствует выбранному шаблону";
                        artifacts = List.of("notes.json", "notes.md");
                    } else if (!completed || !notes) {
                        decision = RerunPlan.Decision.RERUN;
                        reason = "Конспект отсутствует или предыдущая обработка не завершена";
                    } else {
                        decision = RerunPlan.Decision.RERUN;
                        reason = "Изменился формат/шаблон/детализация — конспект будет пересоздан";
                    }
                    produced.add("notes.json");
                    produced.add("notes.md");
                }
                case RAG_INDEXING -> {
                    if (completed) {
                        decision = RerunPlan.Decision.REUSE;
                        reason = "RAG-индекс уже построен";
                        artifacts = List.of("lecture_chunk");
                    } else {
                        decision = RerunPlan.Decision.SKIP;
                        reason = "Индекс RAG не нужен для документов; будет создан при обработке";
                    }
                }
                case SLIDE_EXTRACTION -> {
                    if (!lecture.extractSlides()) {
                        decision = RerunPlan.Decision.SKIP;
                        reason = "Извлечение слайдов отключено для этой лекции";
                    } else if (completed) {
                        decision = RerunPlan.Decision.REUSE;
                        reason = "Изображения слайдов уже извлечены";
                        artifacts = List.of("slides/*.jpg");
                    } else {
                        decision = RerunPlan.Decision.RERUN;
                        reason = "Слайды ещё не извлечены";
                    }
                }
                case EXPORT -> {
                    boolean docx = Files.isRegularFile(storage.resolve(lectureId, "notes.docx"));
                    boolean textRerun = stages.stream()
                            .anyMatch(s -> s.stage() == PipelineStage.TEXT_PROCESSING
                                    && s.decision() == RerunPlan.Decision.RERUN);
                    if (textRerun || !completed || !docx) {
                        decision = RerunPlan.Decision.RERUN;
                        reason = docx ? "Документы будут пересобраны из нового конспекта"
                                : "DOCX ещё не создан";
                    } else {
                        decision = RerunPlan.Decision.REUSE;
                        reason = "Документы уже соответствуют конспекту";
                        artifacts = List.of("notes.docx", "notes.md", "transcript.md");
                    }
                    produced.add("notes.docx");
                }
                default -> {
                    decision = completed ? RerunPlan.Decision.REUSE : RerunPlan.Decision.RERUN;
                    reason = completed ? "Уже выполнено" : "Не выполнялось";
                }
            }
            stages.add(new RerunPlan.StagePlan(stage, decision, reason, artifacts));
            reused.addAll(artifacts.stream()
                    .filter(a -> decision == RerunPlan.Decision.REUSE)
                    .map(a -> stage.title() + ": " + a)
                    .toList());
        }

        if (activeJob) {
            // Block only the stages that would actually have to run.
            stages = stages.stream().map(stage -> stage.decision() == RerunPlan.Decision.RERUN
                    ? new RerunPlan.StagePlan(stage.stage(), RerunPlan.Decision.BLOCKED,
                            activeReason, stage.artifacts())
                    : stage).toList();
        }

        Optional<LectureDocumentRevision> latestNotes =
                revisions.latest(lectureId, DocumentType.NOTES);
        boolean manual = latestNotes.map(revision -> revision.editSource() == EditSource.HUMAN
                || revision.editSource() == EditSource.AI_ASSISTED).orElse(false);
        String manualWarning = manual
                ? "Текущий конспект содержит ручные правки. Они сохраняются в истории редактора, "
                        + "но новый вариант конспекта будет создан отдельно."
                : null;

        if (requested.outputFormat().cleanTranscript()) {
            produced.add("cleaned.txt");
            produced.add("очищенная расшифровка");
        }
        if (lecture.extractSlides()) {
            produced.add("слайды (без повторного извлечения)");
        }
        produced.add("новый вариант в истории результатов");
        if (warnings.isEmpty() && !translationUsable && wantsTranslation) {
            warnings.add("Перевод ещё не готов: конспект может быть создан по оригиналу.");
        }

        return new RerunPlan(lectureId, requested, applied, activeJob, activeReason, stages,
                List.copyOf(reused), List.copyOf(produced), manual, manualWarning,
                List.copyOf(warnings));
    }

    /**
     * What produced the current notes: the newest archived variant when present,
     * otherwise the effective legacy profile.
     */
    private ProcessingProfile appliedProfile(String lectureId) {
        return variants.latest(lectureId)
                .map(variant -> ProcessingProfile.explicit(variant.materialType(),
                        variant.outputFormat(), variant.detailLevel(), variant.promptTemplate()))
                .orElseGet(() -> profiles.get(lectureId));
    }
}
