package com.lectureprocessor.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.pipeline.ArtifactManifest;
import com.lectureprocessor.pipeline.JobState;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.pipeline.StageState;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.repository.StageCheckpointRepository;

/**
 * Regression for the real historical combination reported by the user:
 * {@code lecture=CANCELLED}, {@code pipeline_job=CANCELLED}, active
 * checkpoint={@code RUNNING}, follow-up stages {@code PENDING}.
 *
 * <p>Isolated SQLite database and storage root: user data is never touched.</p>
 */
@SpringBootTest(properties = {
        "app.storage.root=target/it-cancelled-status",
        "spring.datasource.url=jdbc:sqlite:target/it-cancelled-status/cancelled.db",
        "spring.sql.init.mode=always",
        "app.assistant.auto-index=false",
        "app.code-review.enabled=false",
        "app.file-agent.enabled=false"
})
@AutoConfigureMockMvc
class CancelledStatusRegressionTest {

    private static final String ID = "cancelled-regression";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LectureRepository lectureRepository;
    @Autowired
    private PipelineJobRepository jobRepository;
    @Autowired
    private StageCheckpointRepository checkpointRepository;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stage_checkpoint");
        jdbc.update("DELETE FROM pipeline_job");
        jdbc.update("DELETE FROM download_state");
        jdbc.update("DELETE FROM media_source");
        jdbc.update("DELETE FROM lecture_translation");
        jdbc.update("DELETE FROM lecture_language");
        jdbc.update("DELETE FROM lecture_topic");
        jdbc.update("DELETE FROM lecture");

        Instant now = Instant.now();
        lectureRepository.save(new Lecture(ID, "lecture.mp4", "source.mp4", 10, "video/mp4",
                JobStatus.CANCELLED, null, null, false, false, null, null, null, null, now, now));
        jdbc.update("""
                INSERT INTO pipeline_job (lecture_id, state, current_stage, attempts, created_at, updated_at)
                VALUES (?,?,?,?,?,?)
                """, ID, JobState.CANCELLED.name(), PipelineStage.TRANSCRIPTION.name(), 1,
                now.toString(), now.toString());
        for (PipelineStage stage : new PipelineStage[] { PipelineStage.SOURCE_RESOLUTION,
                PipelineStage.DOWNLOADING, PipelineStage.MEDIA_VALIDATION,
                PipelineStage.AUDIO_EXTRACTION }) {
            checkpointRepository.start(ID, stage, "fp-" + stage.name(), 1);
            checkpointRepository.complete(ID, stage, ArtifactManifest.empty());
        }
        checkpointRepository.start(ID, PipelineStage.TRANSCRIPTION, "fp-transcription", 1);
    }

    @Test
    void progressApiExposesTerminalStatusAndRawConflict() throws Exception {
        mockMvc.perform(get("/api/lectures/{id}/progress", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.terminal").value(true))
                .andExpect(jsonPath("$.progressPercent").value(40))
                .andExpect(jsonPath("$.rawStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.stages[4].state").value("CANCELLED"))
                .andExpect(jsonPath("$.stages[4].rawState").value("RUNNING"))
                .andExpect(jsonPath("$.stages[5].state").value("SKIPPED"));
    }

    @Test
    void lectureCardDoesNotLookActiveAndKeepsRawState() throws Exception {
        String html = mockMvc.perform(get("/lectures/{id}", ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Отменено");
        assertThat(html).doesNotContain(">Выполняется<");
        // The conflict stays visible for diagnostics and the polling is stopped.
        assertThat(html).contains("data-raw-status");
        assertThat(html).contains("в базе: RUNNING");
        assertThat(html).doesNotContain("hx-trigger");
    }

    @Test
    void jobsPageDoesNotShowCancelledJobAsRunningAndOffersRetry() throws Exception {
        String html = mockMvc.perform(get("/jobs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Отменено");
        assertThat(html).doesNotContain(">Выполняется<");
        assertThat(html).doesNotContain(">Отменить<");
        assertThat(html).contains("Запустить повторно");
        assertThat(html).contains("data-status=\"CANCELLED\"");
        assertThat(html).contains("data-job-status=\"CANCELLED\"");
    }

    @Test
    void dashboardDoesNotListCancelledJobAsActive() throws Exception {
        String html = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("Сейчас обрабатывается");
        assertThat(html).contains("Отменено");
        assertThat(html).contains("lecture.mp4");
    }

    @Test
    void repeatedCancelOfTerminalJobDoesNotStartWorkOrChangeState() throws Exception {
        mockMvc.perform(post("/lectures/{id}/cancel", ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lectures/" + ID));

        assertThat(jobRepository.find(ID).orElseThrow().state()).isEqualTo(JobState.CANCELLED);
        assertThat(lectureRepository.findById(ID).orElseThrow().status())
                .isEqualTo(JobStatus.CANCELLED);
        // Viewing/cancelling did not rewrite the historical checkpoint.
        assertThat(checkpointRepository.find(ID, PipelineStage.TRANSCRIPTION).orElseThrow().state())
                .isEqualTo(StageState.RUNNING);
    }


    @Test
    void libraryPaginatesAndFiltersRealRows() throws Exception {
        Instant base = Instant.now().minusSeconds(600);
        for (int i = 1; i <= 14; i++) {
            lectureRepository.save(new Lecture("page-" + i, String.format("alpha-%02d.mp4", i),
                    "source.mp4", 10, "video/mp4", JobStatus.COMPLETED, null, null, false, false,
                    null, null, null, null, base.plusSeconds(i), base.plusSeconds(i)));
        }
        jdbc.update("""
                INSERT INTO lecture_language (lecture_id, language_mode, detected_language,
                        translation_mode, target_language, auto_translate, updated_at)
                VALUES (?,?,?,?,?,?,?)
                """, ID, "AUTO", "en", "TRANSLATE_TO_RUSSIAN", "ru", 1, Instant.now().toString());
        jdbc.update("""
                INSERT INTO lecture_translation (lecture_id, source_fingerprint, source_language,
                        target_language, provider, model, prompt_version, glossary_version,
                        settings_hash, status, segments_total, segments_translated, llm_calls,
                        created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, ID, "fp", "en", "ru", "ollama", "test", "v1", "v1", "h", "COMPLETED",
                2, 2, 1, Instant.now().toString(), Instant.now().toString());

        String pageOne = mockMvc.perform(get("/library").param("sort", "title"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(pageOne).contains("Страница 1 из 2");

        String pageTwo = mockMvc.perform(get("/library")
                        .param("status", "COMPLETED").param("sort", "title").param("page", "2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(pageTwo).contains("Страница 2 из 2");
        assertThat(pageTwo).contains("page=1");
        assertThat(pageTwo).contains("status=COMPLETED");

        String failed = mockMvc.perform(get("/library").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(failed).doesNotContain("alpha-01.mp4");
        assertThat(failed).contains("По выбранным условиям ничего нет");

        String byLanguage = mockMvc.perform(get("/library").param("language", "en"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(byLanguage).contains("lecture.mp4");
        assertThat(byLanguage).doesNotContain("alpha-01.mp4");

        String translated = mockMvc.perform(get("/library").param("hasTranslation", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(translated).contains("lecture.mp4");
        assertThat(translated).doesNotContain("alpha-01.mp4");

        String notTranslated = mockMvc.perform(get("/library").param("hasTranslation", "false"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(notTranslated).doesNotContain("lecture.mp4");
        assertThat(notTranslated).contains("library-card");
    }

    @Test
    void viewingPagesDoesNotStartANewWorker() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/jobs")).andExpect(status().isOk());
        mockMvc.perform(get("/library")).andExpect(status().isOk());

        assertThat(jobRepository.find(ID).orElseThrow().state()).isEqualTo(JobState.CANCELLED);
        assertThat(jobRepository.find(ID).orElseThrow().leaseOwner()).isNull();
    }
}
