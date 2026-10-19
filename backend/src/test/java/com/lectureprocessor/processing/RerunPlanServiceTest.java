package com.lectureprocessor.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.editor.DocumentRevisionRepository;
import com.lectureprocessor.editor.DocumentType;
import com.lectureprocessor.editor.EditSource;
import com.lectureprocessor.editor.LectureDocumentRevision;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageRepository;
import com.lectureprocessor.multilingual.MultilingualProperties;
import com.lectureprocessor.multilingual.TranslationRepository;
import com.lectureprocessor.pipeline.JobContext;
import com.lectureprocessor.pipeline.JobState;
import com.lectureprocessor.pipeline.PipelineJob;
import com.lectureprocessor.pipeline.PipelinePlanner;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.text.PromptTemplates;

/**
 * Plan semantics are covered with a stubbed planner so the test asserts exactly
 * what the UI promises. The planner itself (fingerprints vs checkpoints) has its
 * own integration test; both components share the same decisions by design.
 */
class RerunPlanServiceTest {

    private static final String LECTURE_ID = "L1";

    private final ProcessingProfileService profiles = mock(ProcessingProfileService.class);
    private final OutputVariantService variants = mock(OutputVariantService.class);
    private final PipelineJobRepository jobs = mock(PipelineJobRepository.class);
    private final LectureRepository lectures = mock(LectureRepository.class);
    private final TranslationRepository translations = mock(TranslationRepository.class);
    private final LectureLanguageRepository languages = mock(LectureLanguageRepository.class);
    private final DocumentRevisionRepository revisions = mock(DocumentRevisionRepository.class);
    private final PipelinePlanner planner = mock(PipelinePlanner.class);
    private final TemplateCompatibility compatibility = new TemplateCompatibility(new PromptTemplates());

    private RerunPlanService service;
    private JobContext context;
    private List<PipelinePlanner.Evaluation> evaluations;

    @BeforeEach
    void setUp() {
        service = new RerunPlanService(profiles, variants, jobs, lectures, translations, languages,
                MultilingualProperties.defaults(), revisions, planner, compatibility);

        Instant now = Instant.now();
        Lecture lecture = new Lecture(LECTURE_ID, "lecture.mp4", "source.mp4", 100, "video/mp4",
                JobStatus.COMPLETED, null, 600_000L, false, true, null, null, null, null, now, now);
        when(lectures.findById(LECTURE_ID)).thenReturn(Optional.of(lecture));
        when(profiles.get(LECTURE_ID)).thenReturn(ProcessingProfile.legacy("structured-notes-v1"));
        when(variants.latest(LECTURE_ID)).thenReturn(Optional.empty());
        when(jobs.find(LECTURE_ID)).thenReturn(Optional.empty());
        when(translations.findLatest(LECTURE_ID)).thenReturn(Optional.empty());
        when(revisions.latest(LECTURE_ID, DocumentType.NOTES)).thenReturn(Optional.empty());
        LectureLanguage language = LectureLanguage.defaults(LECTURE_ID, "AUTO",
                "TRANSLATE_TO_RUSSIAN", "ru", true)
                .withDetection("ru", null, null);
        when(languages.findOrDefault(any(), any())).thenReturn(language);

        context = new JobContext(LECTURE_ID, new com.lectureprocessor.process.CancellationToken());
        when(planner.previewContext(eq(LECTURE_ID), any())).thenReturn(context);
        evaluations = allReusable();
        when(planner.evaluate(context)).thenAnswer(invocation -> evaluations);
    }

    private List<PipelinePlanner.Evaluation> allReusable() {
        List<PipelinePlanner.Evaluation> list = new ArrayList<>();
        for (PipelineStage stage : PipelineStage.values()) {
            boolean required = stage != PipelineStage.TRANSLATION
                    && stage != PipelineStage.SLIDE_EXTRACTION;
            list.add(new PipelinePlanner.Evaluation(stage, "fp", true, required,
                    required ? PipelinePlanner.Reason.VALID : PipelinePlanner.Reason.NOT_REQUIRED));
        }
        return list;
    }

    private void rerun(PipelineStage stage, PipelinePlanner.Reason reason) {
        evaluations = evaluations.stream().map(e -> e.stage() == stage
                ? new PipelinePlanner.Evaluation(stage, "fp2", false, true, reason)
                : e).toList();
    }

    @Test
    void changingOnlyTheFormatRerunsTextAndExportButNotTranscription() {
        rerun(PipelineStage.TEXT_PROCESSING, PipelinePlanner.Reason.FINGERPRINT_CHANGED);
        rerun(PipelineStage.EXPORT, PipelinePlanner.Reason.FINGERPRINT_CHANGED);
        ProcessingProfile requested = ProcessingProfile.explicit(MaterialType.ACADEMIC_LECTURE,
                OutputFormat.DETAILED_NOTES, DetailLevel.DETAILED, null);

        RerunPlan plan = service.plan(LECTURE_ID, requested);

        assertThat(decision(plan, PipelineStage.TRANSCRIPTION)).isEqualTo(RerunPlan.Decision.REUSE);
        assertThat(decision(plan, PipelineStage.DOWNLOADING)).isEqualTo(RerunPlan.Decision.REUSE);
        assertThat(decision(plan, PipelineStage.TEXT_PROCESSING)).isEqualTo(RerunPlan.Decision.RERUN);
        assertThat(decision(plan, PipelineStage.EXPORT)).isEqualTo(RerunPlan.Decision.RERUN);
        assertThat(plan.needsRerun()).isTrue();
        assertThat(plan.rerunStages()).extracting(RerunPlan.StagePlan::stage)
                .containsExactly(PipelineStage.TEXT_PROCESSING, PipelineStage.EXPORT);
        assertThat(plan.reusedArtifacts()).anyMatch(a -> a.contains("transcript.json"));
        assertThat(plan.producedArtifacts()).contains("notes.docx", "notes.md",
                "новый вариант в истории результатов");
    }

    @Test
    void unchangedLegacyProfileIsANoopAndPromisesNoArtifacts() {
        RerunPlan plan = service.plan(LECTURE_ID, ProcessingProfile.legacy("structured-notes-v1"));

        assertThat(plan.needsRerun()).isFalse();
        assertThat(plan.noop()).isTrue();
        assertThat(plan.producedArtifacts()).isEmpty();
        assertThat(decision(plan, PipelineStage.TEXT_PROCESSING))
                .isEqualTo(RerunPlan.Decision.REUSE);
        assertThat(decision(plan, PipelineStage.EXPORT)).isEqualTo(RerunPlan.Decision.REUSE);
    }

    @Test
    void activeJobBlocksOnlyStagesThatWouldRun() {
        rerun(PipelineStage.TEXT_PROCESSING, PipelinePlanner.Reason.FINGERPRINT_CHANGED);
        when(jobs.find(LECTURE_ID)).thenReturn(Optional.of(new PipelineJob(LECTURE_ID,
                JobState.RUNNING, PipelineStage.TEXT_PROCESSING, 1, "owner",
                Instant.now().plusSeconds(600), null, Instant.now(), Instant.now())));

        RerunPlan plan = service.plan(LECTURE_ID, ProcessingProfile.explicit(
                MaterialType.INTERVIEW, OutputFormat.INTERVIEW_QA, DetailLevel.STANDARD, null));

        assertThat(plan.blocked()).isTrue();
        assertThat(plan.blockedReason()).contains("уже выполняется");
        assertThat(decision(plan, PipelineStage.TEXT_PROCESSING))
                .isEqualTo(RerunPlan.Decision.BLOCKED);
        assertThat(decision(plan, PipelineStage.TRANSCRIPTION)).isEqualTo(RerunPlan.Decision.REUSE);
    }

    @Test
    void manualNotesEditProducesWarningWithoutBlocking() {
        rerun(PipelineStage.TEXT_PROCESSING, PipelinePlanner.Reason.FINGERPRINT_CHANGED);
        when(revisions.latest(LECTURE_ID, DocumentType.NOTES)).thenReturn(Optional.of(
                new LectureDocumentRevision(1, LECTURE_ID, DocumentType.NOTES, 2, "text", "hash",
                        null, EditSource.HUMAN, "manual", Instant.now())));

        RerunPlan plan = service.plan(LECTURE_ID, ProcessingProfile.explicit(
                MaterialType.PRACTICAL_TUTORIAL, OutputFormat.STEP_BY_STEP, DetailLevel.DETAILED, null));

        assertThat(plan.manualEditsPresent()).isTrue();
        assertThat(plan.manualEditsWarning()).contains("ручные правки");
        assertThat(plan.blocked()).isFalse();
    }

    @Test
    void missingNotesArtifactForcesTextProcessingRerun() {
        rerun(PipelineStage.TEXT_PROCESSING, PipelinePlanner.Reason.ARTIFACT_MISSING);

        RerunPlan plan = service.plan(LECTURE_ID, ProcessingProfile.legacy("structured-notes-v1"));

        assertThat(decision(plan, PipelineStage.TEXT_PROCESSING)).isEqualTo(RerunPlan.Decision.RERUN);
        assertThat(plan.stages().stream()
                .filter(s -> s.stage() == PipelineStage.TEXT_PROCESSING)
                .findFirst().orElseThrow().reason()).contains("отсутствует");
    }

    @Test
    void incompatibleInterviewTemplateIsWarnedAboutButNotSilentlySwitched() {
        rerun(PipelineStage.TEXT_PROCESSING, PipelinePlanner.Reason.FINGERPRINT_CHANGED);
        ProcessingProfile requested = ProcessingProfile.explicit(MaterialType.WEBINAR,
                OutputFormat.BRIEF_NOTES, DetailLevel.STANDARD, PromptTemplates.NOTES_INTERVIEW);

        RerunPlan plan = service.plan(LECTURE_ID, requested);

        assertThat(plan.requested().resolvedPrompt()).isEqualTo(PromptTemplates.NOTES_INTERVIEW);
        assertThat(plan.compatibility()).isNotEmpty();
        assertThat(plan.warnings()).anyMatch(w -> w.contains("Шаблон интервью"));
    }

    @Test
    void notRequiredStagesAreSkippedAndNotExecuted() {
        RerunPlan plan = service.plan(LECTURE_ID, ProcessingProfile.legacy("structured-notes-v1"));

        assertThat(decision(plan, PipelineStage.TRANSLATION)).isEqualTo(RerunPlan.Decision.SKIP);
        assertThat(decision(plan, PipelineStage.SLIDE_EXTRACTION)).isEqualTo(RerunPlan.Decision.SKIP);
    }

    private static RerunPlan.Decision decision(RerunPlan plan, PipelineStage stage) {
        return plan.stages().stream().filter(s -> s.stage() == stage).findFirst()
                .orElseThrow().decision();
    }
}
