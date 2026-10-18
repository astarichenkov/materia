package com.lectureprocessor.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.lectureprocessor.importing.ImportCapabilities;
import com.lectureprocessor.importing.ImportCapabilitiesService;
import com.lectureprocessor.importing.ImportDeduplicationService;
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
import com.lectureprocessor.pipeline.JobStatusView;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.repository.SegmentRepository;

/**
 * Renders the operational pages through Spring MVC + Thymeleaf. These tests
 * exist because a controller method returning a view name is not proof that the
 * page renders: the original invalid "whitelabel after upload" defect was a
 * template/model mismatch.
 */
@WebMvcTest(controllers = {LectureWebController.class, DiscoveryWebController.class,
        DiagnosticsController.class, AppVersionController.class, LibraryWebController.class,
        AddMaterialWebController.class, JobsWebController.class})
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
    @MockitoBean
    private PipelineJobRepository pipelineJobs;
    @MockitoBean
    private LectureRepository lectureRepository;
    @MockitoBean
    private ImportCapabilitiesService importCapabilities;
    @MockitoBean
    private ImportDeduplicationService importDeduplication;

    private static Lecture lecture() {
        Instant now = Instant.now();
        return new Lecture("l1", "lecture.mp4", "source.mp4", 100, "video/mp4",
                JobStatus.UPLOADED, null, null, false, false, null, null, null, null, now, now);
    }

    private void mockImportCapabilities() {
        when(importCapabilities.capabilities()).thenReturn(new ImportCapabilities(
                List.of(".mp4", ".mkv"), 4_294_967_296L, List.of("YouTube", "Rutube"),
                true, true, "720p", false));
    }

    private void mockEmptyDashboard() {
        when(library.searchPage(any(), any(), any(), any(), anyString(), anyInt(),
                anyInt())).thenReturn(new LectureLibraryService.LibraryPage(
                        List.of(), 0, 1, 6, 0));
        when(library.countAll()).thenReturn(0L);
        when(library.recentByStatuses(any(), anyInt())).thenReturn(List.of());
        when(pipelineJobs.findActive(anyInt())).thenReturn(List.of());
    }

    @Test
    void libraryPageRenders() throws Exception {
        mockEmptyDashboard();
        mockImportCapabilities();

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Добавить материал")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Найти видео")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Пока нет ни одного материала")));

        mockMvc.perform(get("/add"))
                .andExpect(status().isOk())
                .andExpect(view().name("add"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Локальный файл")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Видео по ссылке")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Добавить и начать обработку")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"requestId\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("action=\"/lectures\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("action=\"/lectures/from-url\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/import-wizard.js")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("href=\"/support\""))));
    }

    @Test
    void layoutShellRendersWithLocalAssetsAndNavigation() throws Exception {
        mockEmptyDashboard();

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"viewport\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/vendor/htmx.min.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"app-sidebar\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Добавить материал")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Задачи обработки")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("href=\"/support\""))));
    }

    @Test
    void workspacePagesRender() throws Exception {
        mockImportCapabilities();
        when(library.searchPage(any(), any(), any(), any(), anyString(), anyInt(),
                anyInt())).thenReturn(new LectureLibraryService.LibraryPage(
                        List.of(), 0, 1, 12, 0));
        when(library.languages()).thenReturn(List.of());
        when(library.countAll()).thenReturn(0L);
        when(pipelineJobs.findRecent(anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/library"))
                .andExpect(status().isOk())
                .andExpect(view().name("library"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Библиотека")));
        mockMvc.perform(get("/add"))
                .andExpect(status().isOk())
                .andExpect(view().name("add"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Добавить материал")));
        mockMvc.perform(get("/jobs"))
                .andExpect(status().isOk())
                .andExpect(view().name("jobs"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Задачи обработки")));
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
    void discoveryResultsOfferWizardHandoff() throws Exception {
        when(lectureService.findAll()).thenReturn(List.of());
        when(discovery.providerStatuses()).thenReturn(List.of());
        when(discovery.recent()).thenReturn(List.of());
        com.lectureprocessor.discovery.DiscoveredVideo video =
                new com.lectureprocessor.discovery.DiscoveredVideo(1L, "youtube", "abc",
                        "https://www.youtube.com/watch?v=abc", "Lecture 1", "description",
                        "Channel", 600_000L, "en", Instant.now(), null, "ml", 0.9,
                        "совпадение по названию", null);
        when(discovery.search(any())).thenReturn(
                new VideoDiscoveryService.DiscoverySearchResponse("ml", List.of("ml"),
                        List.of(video), List.of(), List.of(), "title", false));

        mockMvc.perform(get("/discover").param("query", "ml"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Открыть в мастере")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/add?url=")));
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
    void statusFragmentRendersDownloadBytesWithoutTotal() throws Exception {
        when(lectureService.getRequired("l1")).thenReturn(lecture());
        when(jobStatusService.view("l1")).thenReturn(new JobStatusView(
                "l1", "DOWNLOADING", "DOWNLOADING", "RUNNING", "RUNNING", "DOWNLOADING",
                "Скачивание", 2, 10, 20, "", new JobStatusView.DownloadView("DOWNLOADING",
                        87_171_045L, null, null, 95_000.0, null, null), List.of(), List.of(),
                false));

        mockMvc.perform(get("/lectures/l1/status"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("скачано")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("МБ")));
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

