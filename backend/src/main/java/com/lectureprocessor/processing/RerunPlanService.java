package com.lectureprocessor.processing;

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
import com.lectureprocessor.pipeline.JobContext;
import com.lectureprocessor.pipeline.JobState;
import com.lectureprocessor.pipeline.PipelineJob;
import com.lectureprocessor.pipeline.PipelinePlanner;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;

/**
 * Builds a {@link RerunPlan} without starting anything.
 *
 * <p>The plan delegates every "will this stage run?" decision to
 * {@link PipelinePlanner}, the same component the coordinator uses. Preview and
 * execution therefore share the fingerprints, the profile override and the
 * artifact checks — a plan can no longer promise {@code REUSE} while the
 * execution silently runs the stage.</p>
 */
@Service
public class RerunPlanService {

    private final ProcessingProfileService profiles;
    private final OutputVariantService variants;
    private final PipelineJobRepository jobs;
    private final LectureRepository lectures;
    private final TranslationRepository translations;
    private final LectureLanguageRepository languages;
    private final MultilingualProperties multilingual;
    private final DocumentRevisionRepository revisions;
    private final PipelinePlanner planner;
    private final TemplateCompatibility compatibility;
    private final com.lectureprocessor.scenario.ScenarioCompatibility scenarioCompatibility;

    public RerunPlanService(ProcessingProfileService profiles, OutputVariantService variants,
            PipelineJobRepository jobs, LectureRepository lectures,
            TranslationRepository translations, LectureLanguageRepository languages,
            MultilingualProperties multilingual, DocumentRevisionRepository revisions,
            PipelinePlanner planner, TemplateCompatibility compatibility,
            com.lectureprocessor.scenario.ScenarioCompatibility scenarioCompatibility) {
        this.profiles = profiles;
        this.variants = variants;
        this.jobs = jobs;
        this.lectures = lectures;
        this.translations = translations;
        this.languages = languages;
        this.multilingual = multilingual;
        this.revisions = revisions;
        this.planner = planner;
        this.compatibility = compatibility;
        this.scenarioCompatibility = scenarioCompatibility;
    }

    public RerunPlan plan(String lectureId, ProcessingProfile requested) {
        Lecture lecture = lectures.findById(lectureId).orElseThrow(
                () -> new com.lectureprocessor.lecture.LectureNotFoundException(lectureId));
        ProcessingProfile applied = appliedProfile(lectureId);
        JobContext context = planner.previewContext(lectureId, requested);
        Map<PipelineStage, PipelinePlanner.Evaluation> evaluations = planner.evaluate(context).stream()
                .collect(Collectors.toMap(PipelinePlanner.Evaluation::stage, Function.identity(),
                        (a, b) -> a));

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
            PipelinePlanner.Evaluation evaluation = evaluations.get(stage);
            RerunPlan.Decision decision;
            String reason;
            List<String> artifacts = List.of();
            if (evaluation != null && !evaluation.required()) {
                decision = RerunPlan.Decision.SKIP;
                reason = stage == PipelineStage.TRANSLATION
                        ? "Перевод отключён в настройках языка"
                        : "Извлечение слайдов отключено для этого материала";
            } else if (evaluation != null && evaluation.reusable()) {
                decision = RerunPlan.Decision.REUSE;
                reason = reuseReason(stage, profileChanged, wantsTranslation);
                artifacts = reuseArtifacts(stage);
            } else {
                decision = RerunPlan.Decision.RERUN;
                reason = rerunReason(stage, evaluation, profileChanged, wantsTranslation,
                        translationUsable);
                produced.addAll(producedArtifacts(stage, requested));
            }
            stages.add(new RerunPlan.StagePlan(stage, decision, reason, artifacts));
            if (decision == RerunPlan.Decision.REUSE) {
                reused.addAll(artifacts.stream().map(a -> stage.title() + ": " + a).toList());
            }
        }

        // A stage that reruns rewrites artifacts consumed by later stages. Those
        // later stages will therefore run too (their own fingerprint changes when
        // the file is written). The preview must say so instead of promising
        // REUSE for EXPORT while the execution actually re-exports.
        stages = propagateDependents(stages);

        if (activeJob) {
            stages = stages.stream().map(stage -> stage.decision() == RerunPlan.Decision.RERUN
                    ? new RerunPlan.StagePlan(stage.stage(), RerunPlan.Decision.BLOCKED,
                            activeReason, stage.artifacts())
                    : stage).toList();
        }
        produced.clear();
        for (RerunPlan.StagePlan stage : stages) {
            if (stage.decision() == RerunPlan.Decision.RERUN) {
                produced.addAll(producedArtifacts(stage.stage(), requested));
            }
        }

        boolean needsRerun = stages.stream().anyMatch(RerunPlan.StagePlan::reruns);
        if (!needsRerun) {
            // Never promise artifacts for a no-op plan (BUG-5).
            produced.clear();
        } else {
            produced.add("новый вариант в истории результатов");
        }

        Optional<LectureDocumentRevision> latestNotes =
                revisions.latest(lectureId, DocumentType.NOTES);
        boolean manual = latestNotes.map(revision -> revision.editSource() == EditSource.HUMAN
                || revision.editSource() == EditSource.AI_ASSISTED).orElse(false);
        String manualWarning = manual
                ? "Текущий конспект содержит ручные правки. Они сохраняются в истории редактора, "
                        + "но новый вариант конспекта будет создан отдельно."
                : null;

        if (!translationUsable && wantsTranslation) {
            warnings.add("Перевод ещё не готов: конспект может быть создан по оригиналу.");
        }
        List<TemplateCompatibility.Issue> compatibilityIssues = scenarioCompatibility
                .evaluate(requested);
        for (TemplateCompatibility.Issue issue : compatibilityIssues) {
            if (issue.severity() != TemplateCompatibility.Severity.INFO) {
                warnings.add(issue.message());
            }
        }

        return new RerunPlan(lectureId, requested, applied, activeJob, activeReason, stages,
                List.copyOf(reused), List.copyOf(produced), manual, manualWarning,
                List.copyOf(warnings), List.copyOf(compatibilityIssues));
    }

    /** True when the requested profile would create a new artifact. */
    public boolean needsRerun(String lectureId, ProcessingProfile requested) {
        return plan(lectureId, requested).needsRerun();
    }

    private String reuseReason(PipelineStage stage, boolean profileChanged,
            boolean wantsTranslation) {
        return switch (stage) {
            case SOURCE_RESOLUTION -> "Источник уже определён";
            case DOWNLOADING -> "Видео уже скачано или загружено локально";
            case MEDIA_VALIDATION -> "Медиафайл уже проверен";
            case AUDIO_EXTRACTION -> "Аудио уже извлечено";
            case TRANSCRIPTION -> "Транскрипция уже готова — Whisper не запускается";
            case TRANSLATION -> wantsTranslation
                    ? "Перевод уже готов и не менялся" : "Перевод не требуется";
            case TEXT_PROCESSING -> profileChanged
                    ? "Конспект уже создан с тем же шаблоном"
                    : "Конспект соответствует выбранному шаблону";
            case RAG_INDEXING -> "RAG-индекс уже построен";
            case SLIDE_EXTRACTION -> "Изображения слайдов уже извлечены";
            case EXPORT -> "Документы уже соответствуют конспекту";
        };
    }

    private List<String> reuseArtifacts(PipelineStage stage) {
        return switch (stage) {
            case SOURCE_RESOLUTION -> List.of("источник");
            case DOWNLOADING -> List.of("медиафайл");
            case MEDIA_VALIDATION -> List.of("метаданные медиа");
            case AUDIO_EXTRACTION -> List.of("audio.wav");
            case TRANSCRIPTION -> List.of("transcript.json");
            case TRANSLATION -> List.of("перевод");
            case TEXT_PROCESSING -> List.of("notes.json", "notes.md");
            case RAG_INDEXING -> List.of("lecture_chunk");
            case SLIDE_EXTRACTION -> List.of("slides/*.jpg");
            case EXPORT -> List.of("notes.docx", "notes.md", "transcript.md");
        };
    }

    private String rerunReason(PipelineStage stage, PipelinePlanner.Evaluation evaluation,
            boolean profileChanged, boolean wantsTranslation, boolean translationUsable) {
        PipelinePlanner.Reason cause = evaluation == null
                ? PipelinePlanner.Reason.NO_CHECKPOINT : evaluation.reason();
        return switch (cause) {
            case FINGERPRINT_CHANGED -> switch (stage) {
                case TEXT_PROCESSING -> profileChanged
                        ? "Изменился формат/шаблон/детализация — конспект будет пересоздан"
                        : "Изменилась модель, температура или входной текст — конспект будет пересоздан";
                case EXPORT -> "Изменились входные документы или версия шаблона DOCX";
                case TRANSCRIPTION -> "Изменилась модель распознавания или язык";
                case TRANSLATION -> "Изменился язык, модель перевода или глоссарий";
                case RAG_INDEXING -> "Изменился транскрипт или параметры индекса";
                case SLIDE_EXTRACTION -> "Изменились параметры извлечения слайдов";
                default -> "Изменились входные данные этапа";
            };
            case ARTIFACT_MISSING -> switch (stage) {
                case TEXT_PROCESSING -> "Файл конспекта отсутствует — конспект будет создан заново";
                case EXPORT -> "DOCX или Markdown отсутствует — документы будут созданы заново";
                case TRANSCRIPTION -> "Файл транскрипции отсутствует — потребуется Whisper";
                case AUDIO_EXTRACTION -> "Аудиофайл отсутствует — будет извлечён заново";
                default -> "Обязательный артефакт этапа отсутствует";
            };
            case NOT_COMPLETED -> "Предыдущая обработка этапа не завершена";
            case NO_CHECKPOINT -> switch (stage) {
                case TEXT_PROCESSING -> "Конспект отсутствует или предыдущая обработка не завершена";
                case TRANSCRIPTION -> "Транскрипция отсутствует — потребуется Whisper";
                case TRANSLATION -> wantsTranslation
                        ? "Перевод ещё не выполнен или неполный" : "Перевод не требуется";
                case RAG_INDEXING -> "RAG-индекс ещё не построен";
                case SLIDE_EXTRACTION -> "Слайды ещё не извлечены";
                case EXPORT -> "DOCX ещё не создан";
                default -> "Этап ещё не выполнялся";
            };
            default -> "Этап будет выполнен заново";
        };
    }

    /**
     * Dependency propagation for the preview. Media changes invalidate the whole
     * downstream chain; a new transcript invalidates translation/notes/RAG/export;
     * new notes or slides invalidate export.
     */
    private List<RerunPlan.StagePlan> propagateDependents(List<RerunPlan.StagePlan> stages) {
        java.util.Set<PipelineStage> rerun = new java.util.HashSet<>();
        stages.stream().filter(RerunPlan.StagePlan::reruns)
                .forEach(stage -> rerun.add(stage.stage()));
        boolean mediaChanged = rerun.contains(PipelineStage.SOURCE_RESOLUTION)
                || rerun.contains(PipelineStage.DOWNLOADING)
                || rerun.contains(PipelineStage.MEDIA_VALIDATION);
        boolean transcriptChanged = mediaChanged
                || rerun.contains(PipelineStage.AUDIO_EXTRACTION)
                || rerun.contains(PipelineStage.TRANSCRIPTION);
        boolean translationChanged = transcriptChanged || rerun.contains(PipelineStage.TRANSLATION);
        boolean textChanged = translationChanged || rerun.contains(PipelineStage.TEXT_PROCESSING);
        if (mediaChanged) {
            rerun.add(PipelineStage.MEDIA_VALIDATION);
            rerun.add(PipelineStage.AUDIO_EXTRACTION);
        }
        if (transcriptChanged) {
            rerun.add(PipelineStage.TRANSCRIPTION);
            rerun.add(PipelineStage.TRANSLATION);
            rerun.add(PipelineStage.RAG_INDEXING);
        }
        if (translationChanged) {
            rerun.add(PipelineStage.TEXT_PROCESSING);
        }
        if (textChanged) {
            rerun.add(PipelineStage.EXPORT);
        }
        if (rerun.contains(PipelineStage.SLIDE_EXTRACTION)) {
            rerun.add(PipelineStage.EXPORT);
        }
        return stages.stream().map(stage -> {
            if (rerun.contains(stage.stage()) && stage.decision() == RerunPlan.Decision.REUSE) {
                return new RerunPlan.StagePlan(stage.stage(), RerunPlan.Decision.RERUN,
                        "Зависит от изменённого этапа — будет выполнено после него",
                        stage.artifacts());
            }
            return stage;
        }).toList();
    }

    private List<String> producedArtifacts(PipelineStage stage, ProcessingProfile requested) {
        return switch (stage) {
            case TEXT_PROCESSING -> {
                List<String> artifacts = new ArrayList<>(List.of("notes.json", "notes.md"));
                if (requested.outputFormat().cleanTranscript()) {
                    artifacts.add("cleaned.txt");
                    artifacts.add("очищенная расшифровка");
                }
                yield artifacts;
            }
            case EXPORT -> List.of("notes.docx");
            case TRANSLATION -> List.of("перевод");
            case TRANSCRIPTION -> List.of("transcript.json");
            case AUDIO_EXTRACTION -> List.of("audio.wav");
            case RAG_INDEXING -> List.of("RAG-индекс");
            case SLIDE_EXTRACTION -> List.of("slides/*.jpg");
            default -> List.of();
        };
    }

    /**
     * What produced the current notes: the newest archived variant when present,
     * otherwise the effective legacy profile.
     */
    private ProcessingProfile appliedProfile(String lectureId) {
        return variants.latest(lectureId)
                .map(variant -> ProcessingProfile.explicit(variant.materialType(),
                        variant.outputFormat(), variant.detailLevel(), variant.promptTemplate(),
                        variant.scenario()))
                .orElseGet(() -> profiles.get(lectureId));
    }
}
