package com.lectureprocessor.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.editor.DocumentRevisionRepository;
import com.lectureprocessor.editor.DocumentType;
import com.lectureprocessor.editor.EditSource;
import com.lectureprocessor.editor.LectureDocumentRevision;
import com.lectureprocessor.multilingual.LanguageMode;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageRepository;
import com.lectureprocessor.multilingual.MultilingualProperties;
import com.lectureprocessor.multilingual.TranslationMode;
import com.lectureprocessor.multilingual.TranslationRepository;
import com.lectureprocessor.pipeline.JobState;
import com.lectureprocessor.pipeline.PipelineJob;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.pipeline.StageCheckpoint;
import com.lectureprocessor.pipeline.StageState;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.repository.StageCheckpointRepository;
import com.lectureprocessor.storage.FileStorageService;

class RerunPlanServiceTest {

    private static final String LECTURE_ID = "L1";

    @TempDir
    Path tempDir;

    private final ProcessingProfileService profiles = mock(ProcessingProfileService.class);
    private final OutputVariantService variants = mock(OutputVariantService.class);
    private final StageCheckpointRepository checkpoints = mock(StageCheckpointRepository.class);
    private final PipelineJobRepository jobs = mock(PipelineJobRepository.class);
    private final LectureRepository lectures = mock(LectureRepository.class);
    private final TranslationRepository translations = mock(TranslationRepository.class);
    private final LectureLanguageRepository languages = mock(LectureLanguageRepository.class);
    private final DocumentRevisionRepository revisions = mock(DocumentRevisionRepository.class);

    private FileStorageService storage;
    private RerunPlanService service;

    @BeforeEach
    void setUp() throws Exception {
        storage = new FileStorageService(
                com.lectureprocessor.support.TestProperties.defaults(tempDir.toString()));
        service = new RerunPlanService(profiles, variants, checkpoints, jobs, lectures,
                translations, languages, MultilingualProperties.defaults(), revisions, storage);

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
        when(languages.findOrDefault(anyString(), any())).thenReturn(language);

        List<StageCheckpoint> completed = new ArrayList<>();
        for (PipelineStage stage : PipelineStage.values()) {
            completed.add(new StageCheckpoint(LECTURE_ID, stage, StageState.COMPLETED, "fp", 1,
                    now, now, null, com.lectureprocessor.pipeline.ArtifactManifest.empty()));
        }
        when(checkpoints.findByLecture(LECTURE_ID)).thenReturn(completed);

        write("source.mp4");
        write("audio.wav");
        write("transcript.json");
        write("notes.json");
        write("notes.docx");
    }

    @Test
    void changingOnlyTheFormatRerunsTextAndExportButNotTranscription() {
        ProcessingProfile requested = ProcessingProfile.explicit(MaterialType.ACADEMIC_LECTURE,
                OutputFormat.DETAILED_NOTES, DetailLevel.DETAILED, null);
        RerunPlan plan = service.plan(LECTURE_ID, requested);

        assertThat(decision(plan, PipelineStage.TRANSCRIPTION)).isEqualTo(RerunPlan.Decision.REUSE);
        assertThat(decision(plan, PipelineStage.DOWNLOADING)).isEqualTo(RerunPlan.Decision.REUSE);
        assertThat(decision(plan, PipelineStage.TEXT_PROCESSING)).isEqualTo(RerunPlan.Decision.RERUN);
        assertThat(decision(plan, PipelineStage.EXPORT)).isEqualTo(RerunPlan.Decision.RERUN);
        assertThat(plan.needsRerun()).isTrue();
        assertThat(plan.rerunStages()).extracting(s -> s.stage())
                .containsExactly(PipelineStage.TEXT_PROCESSING, PipelineStage.EXPORT);
        assertThat(plan.reusedArtifacts()).anyMatch(a -> a.contains("transcript.json"));
    }

    @Test
    void unchangedLegacyProfileReusesEverything() {
        RerunPlan plan = service.plan(LECTURE_ID, ProcessingProfile.legacy("structured-notes-v1"));

        assertThat(plan.needsRerun()).isFalse();
        assertThat(decision(plan, PipelineStage.TEXT_PROCESSING))
                .isEqualTo(RerunPlan.Decision.REUSE);
        assertThat(decision(plan, PipelineStage.EXPORT)).isEqualTo(RerunPlan.Decision.REUSE);
    }

    @Test
    void activeJobBlocksOnlyStagesThatWouldRun() {
        when(jobs.find(LECTURE_ID)).thenReturn(Optional.of(new PipelineJob(LECTURE_ID,
                JobState.RUNNING, PipelineStage.TEXT_PROCESSING, 1, "owner", Instant.now().plusSeconds(600),
                null, Instant.now(), Instant.now())));

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
    void missingNotesArtifactForcesTextProcessingRerun() throws Exception {
        Files.delete(storage.resolve(LECTURE_ID, "notes.json"));

        RerunPlan plan = service.plan(LECTURE_ID, ProcessingProfile.legacy("structured-notes-v1"));

        assertThat(decision(plan, PipelineStage.TEXT_PROCESSING)).isEqualTo(RerunPlan.Decision.RERUN);
        assertThat(plan.stages().stream()
                .filter(s -> s.stage() == PipelineStage.TEXT_PROCESSING)
                .findFirst().orElseThrow().reason()).contains("отсутствует");
    }

    private void write(String relative) throws Exception {
        Path path = storage.resolve(LECTURE_ID, relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "artifact");
    }

    private static RerunPlan.Decision decision(RerunPlan plan, PipelineStage stage) {
        return plan.stages().stream().filter(s -> s.stage() == stage).findFirst()
                .orElseThrow().decision();
    }
}
