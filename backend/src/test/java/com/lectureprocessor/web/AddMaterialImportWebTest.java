package com.lectureprocessor.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lectureprocessor.benchmark.BenchmarkRunService;
import com.lectureprocessor.benchmark.BenchmarkService;
import com.lectureprocessor.benchmark.BenchmarkUsageService;
import com.lectureprocessor.chat.LectureChatService;
import com.lectureprocessor.discovery.LocalSimilarityService;
import com.lectureprocessor.discovery.VideoDiscoveryService;
import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.download.MediaDownloadException;
import com.lectureprocessor.download.UnsafeMediaUrlException;
import com.lectureprocessor.importing.ImportCapabilities;
import com.lectureprocessor.importing.ImportCapabilitiesService;
import com.lectureprocessor.importing.ImportDeduplicationService;
import com.lectureprocessor.job.JobQueueFullException;
import com.lectureprocessor.job.LectureJobService;
import com.lectureprocessor.lecture.InvalidUploadException;
import com.lectureprocessor.lecture.LectureLibraryService;
import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.lecture.RemoteLectureService;
import com.lectureprocessor.llm.LlmSettingsService;
import com.lectureprocessor.multilingual.LectureLanguageService;
import com.lectureprocessor.pipeline.JobStatusService;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.processing.OutputVariantService;
import com.lectureprocessor.processing.ProcessingProfileService;
import com.lectureprocessor.processing.RerunPlanService;
import com.lectureprocessor.repository.SegmentRepository;
import com.lectureprocessor.text.PromptTemplates;
import com.lectureprocessor.thumbnail.ThumbnailJobQueue;
import com.lectureprocessor.thumbnail.ThumbnailService;

/**
 * Behaviour of the UX-4 import wizard wiring: the page must reuse the existing
 * endpoints, errors must stay inside the wizard, and a repeated submit must not
 * create a second lecture.
 */
@WebMvcTest(controllers = {LectureWebController.class, AddMaterialWebController.class})
class AddMaterialImportWebTest {

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
    private PipelineJobRepository pipelineJobs;
    @MockitoBean
    private LectureRepository lectureRepository;
    @MockitoBean
    private ImportCapabilitiesService importCapabilities;
    @MockitoBean
    private ImportDeduplicationService importDeduplication;
    @MockitoBean
    private ThumbnailService thumbnails;
    @MockitoBean
    private ThumbnailJobQueue thumbnailQueue;
    @MockitoBean
    private ProcessingProfileService processingProfiles;
    @MockitoBean
    private RerunPlanService rerunPlans;
    @MockitoBean
    private OutputVariantService outputVariants;
    @MockitoBean
    private PromptTemplates promptTemplates;

    @BeforeEach
    void setUp() {
        when(importCapabilities.capabilities()).thenReturn(new ImportCapabilities(
                List.of(".mp4", ".mkv", ".mov"), 4_294_967_296L,
                List.of("YouTube", "VK Видео", "VK", "Rutube"), true, true, "720p", false));
        when(importDeduplication.previousRequest(any())).thenReturn(Optional.empty());
        when(importDeduplication.existingLectureForUrl(any())).thenReturn(Optional.empty());
    }

    @Test
    void wizardRendersOnlySupportedOptionsAndReadOnlyModel() throws Exception {
        mockMvc.perform(get("/add"))
                .andExpect(status().isOk())
                .andExpect(view().name("add"))
                .andExpect(content().string(containsString("Локальный файл")))
                .andExpect(content().string(containsString("Видео по ссылке")))
                .andExpect(content().string(containsString("Найти видео")))
                .andExpect(content().string(containsString("Форматы: MP4, MKV, MOV")))
                // Local audio formats are explicitly not promised.
                .andExpect(content().string(containsString("MP3, M4A, WAV")))
                // No per-job model select exists in the backend.
                .andExpect(content().string(containsString("Выбор модели для отдельного материала")))
                .andExpect(content().string(containsString("action=\"/lectures/from-url\"")))
                .andExpect(content().string(containsString("hx-get=\"/lectures/preview\"")))
                .andExpect(content().string(containsString("/js/import-wizard.js")));
    }

    @Test
    void quickUrlIsPrefilledAndSelectsTheUrlSource() throws Exception {
        mockMvc.perform(get("/add").param("url", "https://youtu.be/abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"https://youtu.be/abc\"")))
                .andExpect(content().string(containsString("data-source=\"url\"")));
    }

    @Test
    void uploadSuccessOpensLectureAndRemembersRequest() throws Exception {
        Lecture lecture = lecture("l1");
        when(lectureService.create(any(), anyBoolean(), anyBoolean(), anyString(), anyString(),
                anyBoolean(), anyString())).thenReturn(lecture);
        when(jobService.submit("l1")).thenReturn(true);

        mockMvc.perform(multipart("/lectures")
                        .file(file())
                        .param("requestId", "req-1")
                        .param("languageMode", "ENGLISH"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lectures/l1"))
                .andExpect(flash().attribute("message", containsString("обработка запущена")));

        verify(importDeduplication).remember("req-1", "l1");
    }

    @Test
    void repeatedUploadWithSameRequestIdDoesNotCreateSecondLecture() throws Exception {
        when(importDeduplication.previousRequest("req-1")).thenReturn(Optional.of("l1"));

        mockMvc.perform(multipart("/lectures").file(file()).param("requestId", "req-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lectures/l1"))
                .andExpect(flash().attribute("message", containsString("уже добавлен")));

        verify(lectureService, never()).create(any(), anyBoolean(), anyBoolean(), anyString(),
                anyString(), anyBoolean(), anyString());
        verify(jobService, never()).submit(anyString());
    }

    @Test
    void invalidUploadReturnsToWizardWithReason() throws Exception {
        when(lectureService.create(any(), anyBoolean(), anyBoolean(), anyString(), anyString(),
                anyBoolean(), anyString()))
                .thenThrow(new InvalidUploadException("Неподдерживаемый формат видео: .txt"));

        mockMvc.perform(multipart("/lectures").file(file()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/add"))
                .andExpect(flash().attribute("error", containsString("Неподдерживаемый формат")));

        verify(jobService, never()).submit(anyString());
    }

    @Test
    void fullQueueIsReportedInsteadOfFalseSuccess() throws Exception {
        Lecture lecture = lecture("l1");
        when(lectureService.create(any(), anyBoolean(), anyBoolean(), anyString(), anyString(),
                anyBoolean(), anyString())).thenReturn(lecture);
        when(jobService.submit("l1")).thenThrow(new JobQueueFullException("full"));

        mockMvc.perform(multipart("/lectures").file(file()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lectures/l1"))
                .andExpect(flash().attribute("error", containsString("очередь обработки")));
    }

    @Test
    void invalidUrlKeepsTheEnteredValueAndCreatesNothing() throws Exception {
        when(remoteLectureService.createFromUrl(anyString(), anyString(), anyBoolean(),
                anyBoolean(), anyBoolean(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenThrow(new UnsafeMediaUrlException(
                        "Неподдерживаемая платформа: example.com. Поддерживаются YouTube, VK Видео, VK, Rutube."));

        mockMvc.perform(post("/lectures/from-url")
                        .param("url", "https://example.com/lecture")
                        .param("requestId", "req-url"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith("/add?url="))
                .andExpect(flash().attribute("error", containsString("Неподдерживаемая платформа")))
                .andExpect(flash().attribute("importErrorUrl", "https://example.com/lecture"));

        verify(jobService, never()).submit(anyString());
    }

    @Test
    void downloadFailureShowsClassifiedMessageWithoutRawDetail() throws Exception {
        when(remoteLectureService.createFromUrl(anyString(), anyString(), anyBoolean(),
                anyBoolean(), anyBoolean(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenThrow(new MediaDownloadException(
                        "YouTube требует подтвердить, что запрос сделал не робот. Импорт остановлен.",
                        null, false, "raw yt-dlp stderr with cookies", "BOT_CHECK"));

        String response = mockMvc.perform(post("/lectures/from-url")
                        .param("url", "https://www.youtube.com/watch?v=abc"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith("/add?url="))
                .andExpect(flash().attribute("error", containsString("не робот")))
                .andReturn().getResponse().getRedirectedUrl();

        org.assertj.core.api.Assertions.assertThat(response).doesNotContain("raw");
        verify(jobService, never()).submit(anyString());
    }

    @Test
    void existingUrlOpensExistingLectureInsteadOfDownloadingAgain() throws Exception {
        when(importDeduplication.existingLectureForUrl("https://youtu.be/abc"))
                .thenReturn(Optional.of("existing"));

        mockMvc.perform(post("/lectures/from-url")
                        .param("url", "https://youtu.be/abc")
                        .param("requestId", "req-2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lectures/existing"))
                .andExpect(flash().attribute("message", containsString("уже есть в библиотеке")));

        verify(remoteLectureService, never()).createFromUrl(anyString(), anyString(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyString(), anyString(), anyBoolean(),
                anyString());
    }

    @Test
    void urlImportSuccessStartsProcessing() throws Exception {
        when(remoteLectureService.createFromUrl(eq("https://youtu.be/abc"), eq("720p"), eq(false),
                eq(false), eq(true), eq("ENGLISH"), eq("TRANSLATE_TO_RUSSIAN"), eq(true), eq("ru")))
                .thenReturn(lecture("l9"));
        when(jobService.submit("l9")).thenReturn(true);

        mockMvc.perform(post("/lectures/from-url")
                        .param("url", "https://youtu.be/abc")
                        .param("requestId", "req-3")
                        .param("languageMode", "ENGLISH")
                        .param("slides", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lectures/l9"))
                .andExpect(flash().attribute("message", containsString("обработка запущена")));

        verify(importDeduplication).remember("req-3", "l9");
    }

    @Test
    void previewShowsMetadataAndExistingLectureLink() throws Exception {
        when(remoteLectureService.preview("https://youtu.be/abc"))
                .thenReturn(new com.lectureprocessor.media.MediaMetadata("Lecture 1", "Youtube",
                        "abc", "Channel", 104_000L, 1280, 720, 5_600_000L,
                        "https://youtu.be/abc", List.of(), "{}"));
        when(importDeduplication.existingLectureForUrl("https://youtu.be/abc"))
                .thenReturn(Optional.of("l1"));

        mockMvc.perform(get("/lectures/preview").param("url", "https://youtu.be/abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lecture 1")))
                .andExpect(content().string(containsString("уже есть в библиотеке")))
                .andExpect(content().string(containsString("/lectures/l1")));
    }

    @Test
    void previewErrorOffersFallbacksAndKeepsUrl() throws Exception {
        when(remoteLectureService.preview("https://www.youtube.com/watch?v=abc"))
                .thenThrow(new MediaDownloadException(
                        "Видео недоступно на платформе: удалено, скрыто или ограничено.",
                        null, false, "raw stderr", "UNAVAILABLE"));

        mockMvc.perform(get("/lectures/preview")
                        .param("url", "https://www.youtube.com/watch?v=abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Не удалось получить информацию о видео")))
                .andExpect(content().string(containsString("Загрузить файл")))
                .andExpect(content().string(containsString("Диагностика")))
                .andExpect(content().string(containsString("Ссылка останется в поле")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("raw stderr"))));
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "lecture.mp4", "video/mp4", new byte[] {1, 2, 3});
    }

    private static Lecture lecture(String id) {
        Instant now = Instant.now();
        return new Lecture(id, "lecture.mp4", "source.mp4", 3, "video/mp4",
                JobStatus.UPLOADED, null, null, false, false, null, null, null, null, now, now);
    }
}
