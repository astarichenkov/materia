package com.lectureprocessor.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lectureprocessor.benchmark.BenchmarkRunService;
import com.lectureprocessor.benchmark.BenchmarkService;
import com.lectureprocessor.benchmark.BenchmarkUsageService;
import com.lectureprocessor.chat.LectureChatService;
import com.lectureprocessor.diagnostics.DiagnosticsService;
import com.lectureprocessor.discovery.LocalSimilarityService;
import com.lectureprocessor.discovery.VideoDiscoveryService;
import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.job.LectureJobService;
import com.lectureprocessor.lecture.LectureLibraryService;
import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.lecture.RemoteLectureService;
import com.lectureprocessor.llm.LlmSettingsService;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageService;
import com.lectureprocessor.multilingual.MultilingualProperties;
import com.lectureprocessor.pipeline.JobStatusService;
import com.lectureprocessor.repository.SegmentRepository;

/**
 * Renders the operational pages through Spring MVC + Thymeleaf. These tests
 * exist because a controller method returning a view name is not proof that the
 * page renders: the original invalid "whitelabel after upload" defect was a
 * template/model mismatch.
 */
@WebMvcTest(controllers = {LectureWebController.class, DiscoveryWebController.class,
        DiagnosticsController.class, AppVersionController.class})
class RuntimePagesWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LectureService lectureService;
    @MockitoBean
    private RemoteLectureService remoteLectureService;
    @MockitoBean
    private LectureJobService jobService;
    @MockitoBean
    private JobStatusService jobStatusService;
    @MockitoBean
    private LectureChatService chatService;
    @MockitoBean
    private BenchmarkService benchmarkService;
    @MockitoBean
    private BenchmarkRunService benchmarkRuns;
    @MockitoBean
    private BenchmarkUsageService benchmarkUsage;
    @MockitoBean
    private LlmSettingsService llmSettings;
    @MockitoBean
    private LectureLanguageService languages;
    @MockitoBean
    private LocalSimilarityService similarities;
    @MockitoBean
    private VideoDiscoveryService discovery;
    @MockitoBean
    private SegmentRepository segments;
    @MockitoBean
    private LectureLibraryService library;
    @MockitoBean
    private DiagnosticsService diagnostics;
    @MockitoBean
    private MultilingualProperties multilingual;

    private static Lecture lecture() {
        Instant now = Instant.now();
        return new Lecture("l1", "lecture.mp4", "source.mp4", 100, "video/mp4",
                JobStatus.UPLOADED, null, null, false, false, null, null, null, null, now, now);
    }

    @Test
    void libraryPageRenders() throws Exception {
        when(library.search(any(), any(), any(), any(), anyString())).thenReturn(List.of());
        when(lectureService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Локальный файл")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Видео по ссылке")));
    }

    @Test
    void discoverPageRendersWithoutOnlineSearch() throws Exception {
        when(lectureService.findAll()).thenReturn(List.of());
        when(discovery.providerStatuses()).thenReturn(List.of());
        when(discovery.recent()).thenReturn(List.of());

        mockMvc.perform(get("/discover"))
                .andExpect(status().isOk())
                .andExpect(view().name("discover"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Smart Video Discovery")));
    }

    @Test
    void diagnosticsPageRendersEvenWhenChecksFail() throws Exception {
        when(diagnostics.report()).thenReturn(new DiagnosticsService.DiagnosticsReport(
                List.of(new DiagnosticsService.Check("ffmpeg", "FFmpeg",
                        DiagnosticsService.Status.FAILED, "Не запускается: ffmpeg",
                        "Установите FFmpeg и задайте FFMPEG_PATH.")),
                false, true, Instant.now()));

        mockMvc.perform(get("/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(view().name("diagnostics"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("FFmpeg")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Установите FFmpeg")));
    }

    @Test
    void diagnosticsApiReturnsJson() throws Exception {
        when(diagnostics.report()).thenReturn(new DiagnosticsService.DiagnosticsReport(
                List.of(), true, false, Instant.now()));

        mockMvc.perform(get("/api/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true));
    }

    @Test
    void newLecturePageRendersWithEmptyOptionalData() throws Exception {
        Lecture lecture = lecture();
        LectureLanguage language = LectureLanguage.defaults("l1", "AUTO",
                "TRANSLATE_TO_RUSSIAN", "ru", true);
        when(lectureService.getRequired("l1")).thenReturn(lecture);
        when(jobStatusService.view("l1")).thenReturn(null);
        when(languages.get("l1")).thenReturn(language);
        when(languages.view("l1")).thenReturn(new LectureLanguageService.TranslationView(
                language, null, List.of()));
        when(similarities.cached("l1")).thenReturn(List.of());
        when(discovery.providerStatuses()).thenReturn(List.of());
        when(segments.findByLecture("l1")).thenReturn(List.of());

        mockMvc.perform(get("/lectures/l1"))
                .andExpect(status().isOk())
                .andExpect(view().name("lecture"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Язык и перевод")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Перевод ещё не выполнялся")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Похожих лекций пока нет")));
    }

    @Test
    void statusFragmentRendersForQueuedLecture() throws Exception {
        when(lectureService.getRequired("l1")).thenReturn(lecture());
        when(jobStatusService.view("l1")).thenReturn(null);

        mockMvc.perform(get("/lectures/l1/status"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Статус:")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UPLOADED")));
    }

    @Test
    void versionEndpointExposesBuildWithoutSecrets() throws Exception {
        mockMvc.perform(get("/api/app/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("lecture-processor"))
                .andExpect(jsonPath("$.javaVersion").isNotEmpty())
                .andExpect(jsonPath("$.commit").isNotEmpty());
    }
}

