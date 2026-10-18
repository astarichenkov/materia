package com.lectureprocessor.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import com.lectureprocessor.benchmark.BenchmarkRunService;
import com.lectureprocessor.benchmark.BenchmarkService;
import com.lectureprocessor.benchmark.BenchmarkUsageService;
import com.lectureprocessor.chat.LectureChatService;
import com.lectureprocessor.discovery.LocalSimilarityService;
import com.lectureprocessor.discovery.VideoDiscoveryService;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.download.MediaDownloadException;
import com.lectureprocessor.download.MediaQuality;
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
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmSettingsService;
import com.lectureprocessor.multilingual.LectureLanguageService;
import com.lectureprocessor.pipeline.JobStatusService;
import com.lectureprocessor.pipeline.PipelineJob;
import com.lectureprocessor.processing.OutputFormat;
import com.lectureprocessor.processing.OutputVariantService;
import com.lectureprocessor.processing.ProcessingProfile;
import com.lectureprocessor.processing.ProcessingProfileService;
import com.lectureprocessor.processing.RerunPlan;
import com.lectureprocessor.processing.RerunPlanService;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.repository.SegmentRepository;
import com.lectureprocessor.text.PromptTemplates;
import com.lectureprocessor.thumbnail.ThumbnailJobQueue;
import com.lectureprocessor.thumbnail.ThumbnailService;
import com.lectureprocessor.web.ui.JobRow;
import com.lectureprocessor.web.ui.StatusLabels;

/**
 * Minimal Thymeleaf + HTMX UI: upload, monitor status, download results, chat
 * per lecture and view benchmark config.
 */
@Controller
public class LectureWebController {

    private static final Logger log = LoggerFactory.getLogger(LectureWebController.class);

    private final LectureService lectureService;
    private final RemoteLectureService remoteLectureService;
    private final LectureJobService jobService;
    private final JobStatusService jobStatusService;
    private final LectureChatService chatService;
    private final BenchmarkService benchmarkService;
    private final BenchmarkRunService benchmarkRuns;
    private final BenchmarkUsageService benchmarkUsage;
    private final LlmSettingsService llmSettings;
    private final LectureLanguageService languages;
    private final LocalSimilarityService similarities;
    private final VideoDiscoveryService discovery;
    private final SegmentRepository segments;
    private final LectureLibraryService library;
    private final PipelineJobRepository jobs;
    private final LectureRepository lectures;
    private final ImportDeduplicationService importDeduplication;
    private final ImportCapabilitiesService importCapabilities;
    private final ThumbnailService thumbnails;
    private final ThumbnailJobQueue thumbnailQueue;
    private final ProcessingProfileService profiles;
    private final RerunPlanService rerunPlans;
    private final OutputVariantService variants;
    private final PromptTemplates promptTemplates;

    public LectureWebController(LectureService lectureService, RemoteLectureService remoteLectureService,
            LectureJobService jobService, JobStatusService jobStatusService,
            LectureChatService chatService, BenchmarkService benchmarkService,
            BenchmarkRunService benchmarkRuns, BenchmarkUsageService benchmarkUsage,
            LlmSettingsService llmSettings, LectureLanguageService languages,
            LocalSimilarityService similarities, VideoDiscoveryService discovery,
            SegmentRepository segments, LectureLibraryService library,
            PipelineJobRepository jobs, LectureRepository lectures,
            ImportDeduplicationService importDeduplication,
            ImportCapabilitiesService importCapabilities, ThumbnailService thumbnails,
            ThumbnailJobQueue thumbnailQueue, ProcessingProfileService profiles,
            RerunPlanService rerunPlans, OutputVariantService variants,
            PromptTemplates promptTemplates) {
        this.lectureService = lectureService;
        this.remoteLectureService = remoteLectureService;
        this.jobService = jobService;
        this.jobStatusService = jobStatusService;
        this.chatService = chatService;
        this.benchmarkService = benchmarkService;
        this.benchmarkRuns = benchmarkRuns;
        this.benchmarkUsage = benchmarkUsage;
        this.llmSettings = llmSettings;
        this.languages = languages;
        this.similarities = similarities;
        this.discovery = discovery;
        this.segments = segments;
        this.library = library;
        this.jobs = jobs;
        this.lectures = lectures;
        this.importDeduplication = importDeduplication;
        this.importCapabilities = importCapabilities;
        this.thumbnails = thumbnails;
        this.thumbnailQueue = thumbnailQueue;
        this.profiles = profiles;
        this.rerunPlans = rerunPlans;
        this.variants = variants;
        this.promptTemplates = promptTemplates;
    }

    /**
     * Working dashboard: add material, continue processing, see problems and the
     * latest results. All data comes from existing read-only services.
     */
    @GetMapping("/")
    public String index(Model model) {
        LectureLibraryService.LibraryPage recent = library.searchPage(null, null, null, null,
                "date-desc", 1, 6);
        List<JobRow> activeJobs = new ArrayList<>();
        for (PipelineJob job : jobs.findActive(20)) {
            Lecture lecture = lectures.findById(job.lectureId()).orElse(null);
            if (lecture == null) {
                continue;
            }
            try {
                JobRow row = new JobRow(job, lecture, jobStatusService.view(job.lectureId()));
                if (!row.terminal()) {
                    activeJobs.add(row);
                }
            } catch (RuntimeException ignored) {
                // A broken snapshot must not hide every other task.
            }
        }
        List<LectureLibraryService.LibraryItem> attention = library.recentByStatuses(
                List.of(com.lectureprocessor.domain.JobStatus.FAILED,
                        com.lectureprocessor.domain.JobStatus.PARTIAL,
                        com.lectureprocessor.domain.JobStatus.INTERRUPTED), 5);
        model.addAttribute("recent", recent.items());
        recent.items().forEach(item -> {
            if (!item.hasThumbnail()) {
                thumbnailQueue.enqueue(item.lectureId());
            }
        });
        model.addAttribute("activeJobs", activeJobs);
        model.addAttribute("attention", attention);
        model.addAttribute("anyLectures", library.countAll() > 0);
        model.addAttribute("statusLabels", StatusLabels.all());
        return "index";
    }

    /**
     * The servlet container rejects oversized multipart requests before the
     * handler body runs, so the size limit cannot be reported from inside
     * {@link #upload}. Keeping the handler on this controller avoids a global
     * advice that every test context would have to satisfy.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String uploadTooLarge(MaxUploadSizeExceededException e, RedirectAttributes redirect) {
        String limit = ImportCapabilities.formatBytes(
                importCapabilities.capabilities().maxFileSizeBytes());
        log.info("Upload rejected: request exceeds the configured limit ({})", limit);
        redirect.addFlashAttribute("error", "Файл больше допустимого размера (" + limit
                + "). Выберите файл меньше или сожмите видео перед загрузкой.");
        return "redirect:/add";
    }

    @PostMapping("/lectures")
    public String upload(@RequestParam("file") MultipartFile file,
            @RequestParam(name = "clean", defaultValue = "false") boolean clean,
            @RequestParam(name = "slides", defaultValue = "false") boolean slides,
            @RequestParam(name = "languageMode", defaultValue = "AUTO") String languageMode,
            @RequestParam(name = "translationMode", defaultValue = "TRANSLATE_TO_RUSSIAN")
            String translationMode,
            @RequestParam(name = "autoTranslate", defaultValue = "true") boolean autoTranslate,
            @RequestParam(name = "requestId", required = false) String requestId,
            RedirectAttributes redirect) {
        Optional<String> previous = importDeduplication.previousRequest(requestId);
        if (previous.isPresent()) {
            redirect.addFlashAttribute("message",
                    "Этот материал уже добавлен: повторная отправка не создала новую запись.");
            return "redirect:/lectures/" + previous.get();
        }
        Lecture lecture;
        try {
            lecture = lectureService.create(file, clean, slides, languageMode,
                    translationMode, autoTranslate, "ru");
        } catch (InvalidUploadException e) {
            log.info("Upload rejected: {}", e.getMessage());
            return redirectToWizard(redirect, null, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Upload failed: {}", e.getMessage(), e);
            return redirectToWizard(redirect, null,
                    "Не удалось сохранить файл. Проверьте свободное место и повторите попытку.");
        }
        importDeduplication.remember(requestId, lecture.id());
        return openCreatedLecture(lecture.id(), "Лекция загружена", redirect);
    }

    @PostMapping("/lectures/from-url")
    public String uploadFromUrl(@RequestParam("url") String url,
            @RequestParam(name = "quality", defaultValue = "720p") String quality,
            @RequestParam(name = "audioOnly", defaultValue = "false") boolean audioOnly,
            @RequestParam(name = "clean", defaultValue = "false") boolean clean,
            @RequestParam(name = "slides", defaultValue = "false") boolean slides,
            @RequestParam(name = "languageMode", defaultValue = "AUTO") String languageMode,
            @RequestParam(name = "translationMode", defaultValue = "TRANSLATE_TO_RUSSIAN")
            String translationMode,
            @RequestParam(name = "autoTranslate", defaultValue = "true") boolean autoTranslate,
            @RequestParam(name = "requestId", required = false) String requestId,
            RedirectAttributes redirect) {
        Optional<String> previous = importDeduplication.previousRequest(requestId);
        if (previous.isPresent()) {
            redirect.addFlashAttribute("message",
                    "Этот материал уже добавлен: повторная отправка не создала новую запись.");
            return "redirect:/lectures/" + previous.get();
        }
        Optional<String> existing = importDeduplication.existingLectureForUrl(url);
        if (existing.isPresent()) {
            redirect.addFlashAttribute("message",
                    "Это видео уже есть в библиотеке — открыта существующая лекция.");
            return "redirect:/lectures/" + existing.get();
        }
        Lecture lecture;
        try {
            lecture = remoteLectureService.createFromUrl(url, quality, audioOnly, clean,
                    slides, languageMode, translationMode, autoTranslate, "ru");
        } catch (UnsafeMediaUrlException e) {
            log.info("URL rejected: {}", e.getMessage());
            return redirectToWizard(redirect, url, e.getMessage());
        } catch (MediaDownloadException e) {
            // The message is already a human readable classification; raw tool
            // output stays in the log and is never rendered.
            log.warn("Metadata probe failed ({}): {}", e.reason(), e.getMessage());
            return redirectToWizard(redirect, url, e.getMessage());
        } catch (RuntimeException e) {
            log.error("URL import failed: {}", e.getMessage(), e);
            return redirectToWizard(redirect, url,
                    "Не удалось добавить видео по ссылке. Проверьте диагностику и повторите попытку.");
        }
        importDeduplication.remember(requestId, lecture.id());
        return openCreatedLecture(lecture.id(), "Видео принято в обработку", redirect);
    }

    /**
     * A lecture row already exists; report honestly whether processing started
     * instead of claiming success when the queue is full.
     */
    private String openCreatedLecture(String lectureId, String message, RedirectAttributes redirect) {
        try {
            boolean submitted = jobService.submit(lectureId);
            redirect.addFlashAttribute("message", submitted
                    ? message + ", обработка запущена."
                    : message + ". Обработка уже выполняется или поставлена ранее.");
        } catch (JobQueueFullException e) {
            log.warn("Lecture {} created but the queue is full: {}", lectureId, e.getMessage());
            redirect.addFlashAttribute("error", message
                    + ", но очередь обработки переполнена. Запустите обработку с карточки материала позже.");
        }
        return "redirect:/lectures/" + lectureId;
    }

    /**
     * Sends the user back to the wizard with the entered URL preserved and a
     * readable reason. Only an absolute http(s) URL may be offered as an
     * external link; it is rendered by Thymeleaf, never concatenated into href.
     */
    private String redirectToWizard(RedirectAttributes redirect, String url, String error) {
        redirect.addFlashAttribute("error", error);
        if (url != null && !url.isBlank() && url.length() <= 2048
                && (url.startsWith("http://") || url.startsWith("https://"))) {
            redirect.addFlashAttribute("importErrorUrl", url);
        }
        if (url == null || url.isBlank() || url.length() > 2048) {
            return "redirect:/add";
        }
        return "redirect:" + UriComponentsBuilder.fromPath("/add")
                .queryParam("url", url)
                .build()
                .encode()
                .toUriString();
    }

    @GetMapping("/lectures/preview")
    public String preview(@RequestParam("url") String url, Model model) {
        String candidate = url == null ? "" : url.strip();
        try {
            model.addAttribute("metadata", remoteLectureService.preview(candidate));
            importDeduplication.existingLectureForUrl(candidate)
                    .ifPresent(lectureId -> model.addAttribute("existingLectureId", lectureId));
        } catch (UnsafeMediaUrlException e) {
            model.addAttribute("error", e.getMessage());
        } catch (MediaDownloadException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("retryable", e.retryable());
            model.addAttribute("reason", e.reason());
        } catch (RuntimeException e) {
            log.warn("URL preview failed: {}", e.getMessage());
            model.addAttribute("error",
                    "Не удалось проверить ссылку. Материал можно добавить без предварительной проверки.");
        }
        if (candidate.length() <= 2048
                && (candidate.startsWith("http://") || candidate.startsWith("https://"))) {
            model.addAttribute("previewUrl", candidate);
        }
        model.addAttribute("qualities", MediaQuality.values());
        return "fragments/url-preview :: preview";
    }

    @GetMapping("/lectures/{id}")
    public String lecture(@PathVariable String id, Model model) {
        Lecture lecture = lectureService.getRequired(id);
        model.addAttribute("lecture", lecture);
        model.addAttribute("job", jobStatusService.view(id));
        model.addAttribute("language", languages.get(id));
        model.addAttribute("translation", languages.view(id));
        // Recommendations must never block or break the lecture card. Use the
        // cached rows; a fresh computation is triggered explicitly from /discover.
        try {
            model.addAttribute("similarLectures", similarities.cached(id));
        } catch (RuntimeException e) {
            log.warn("Similar lectures unavailable for {}: {}", id, e.getMessage());
            model.addAttribute("similarLectures", List.of());
        }
        try {
            model.addAttribute("discoveryProviders", discovery.providerStatuses());
        } catch (RuntimeException e) {
            log.warn("Discovery provider status unavailable for {}: {}", id, e.getMessage());
            model.addAttribute("discoveryProviders", List.of());
        }
        model.addAttribute("transcriptSegments", segments.findByLecture(id));
        // Cached poster; generation is queued in the background, never inline.
        boolean thumbnailReady = thumbnails.isReady(id);
        model.addAttribute("thumbnailReady", thumbnailReady);
        if (!thumbnailReady) {
            thumbnailQueue.enqueue(id);
        }
        addProcessingProfileModel(id, model);
        return "lecture";
    }

    /**
     * Adds the output profile form, the produced variants and the pre-computed
     * re-run plan to the lecture page. Everything is read-only derived state.
     */
    private void addProcessingProfileModel(String id, Model model) {
        ProcessingProfile profile = profiles.get(id);
        model.addAttribute("profile", profile);
        model.addAttribute("materialTypes", profiles.materialTypes());
        model.addAttribute("outputFormats", profiles.outputFormats());
        model.addAttribute("detailLevels", profiles.detailLevels());
        model.addAttribute("promptTemplates", notesPrompts());
        model.addAttribute("variants", variants.list(id));
        try {
            model.addAttribute("rerunPlan", rerunPlans.plan(id, profile));
        } catch (RuntimeException e) {
            log.warn("Rerun plan unavailable for {}: {}", id, e.getMessage());
            model.addAttribute("rerunPlan", null);
        }
    }

    /** Prompt templates that can produce a document (assistant/translation excluded). */
    private List<PromptTemplates.Template> notesPrompts() {
        return promptTemplates.list().stream()
                .filter(t -> t.name().startsWith("notes-")
                        || t.name().startsWith("clean-transcript")
                        || t.name().startsWith("structured-notes"))
                .toList();
    }

    /** Preview of the re-run plan for a profile that is not saved yet. */
    @GetMapping("/lectures/{id}/rerun-plan")
    public String rerunPlan(@PathVariable String id,
            @RequestParam(name = "materialType", required = false) String materialType,
            @RequestParam(name = "outputFormat", required = false) String outputFormat,
            @RequestParam(name = "detailLevel", required = false) String detailLevel,
            @RequestParam(name = "promptTemplate", required = false) String promptTemplate,
            Model model) {
        lectureService.getRequired(id);
        ProcessingProfile preview = profiles.preview(materialType, outputFormat, detailLevel,
                promptTemplate);
        model.addAttribute("previewProfile", preview);
        model.addAttribute("rerunPlan", rerunPlans.plan(id, preview));
        model.addAttribute("promptTemplates", notesPrompts());
        return "fragments/rerun-plan :: plan";
    }

    /**
     * Saves the selected profile and starts a re-run. The orchestrator reuses
     * every valid checkpoint: changing only the document format never re-downloads
     * media and never re-runs Whisper.
     */
    @PostMapping("/lectures/{id}/rerun")
    public String rerun(@PathVariable String id,
            @RequestParam(name = "materialType", required = false) String materialType,
            @RequestParam(name = "outputFormat", required = false) String outputFormat,
            @RequestParam(name = "detailLevel", required = false) String detailLevel,
            @RequestParam(name = "promptTemplate", required = false) String promptTemplate,
            RedirectAttributes redirect) {
        lectureService.getRequired(id);
        try {
            ProcessingProfile profile = profiles.save(id, materialType, outputFormat, detailLevel,
                    promptTemplate);
            RerunPlan plan = rerunPlans.plan(id, profile);
            if (plan.blocked()) {
                redirect.addFlashAttribute("error", "Пересоздание не запущено: "
                        + plan.blockedReason());
                return "redirect:/lectures/" + id;
            }
            boolean submitted = jobService.submit(id);
            if (submitted) {
                String formats = OutputFormat.from(outputFormat).label();
                redirect.addFlashAttribute("message", "Профиль «" + formats
                        + "» сохранён, пересоздание запущено. Повторно скачиваться и распознаваться "
                        + "материал не будет.");
            } else {
                redirect.addFlashAttribute("message",
                        "Профиль сохранён. Задача уже выполняется — новый результат появится после "
                                + "её завершения.");
            }
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (JobQueueFullException e) {
            redirect.addFlashAttribute("error",
                    "Профиль сохранён, но очередь обработки переполнена. Запустите пересоздание позже.");
        }
        return "redirect:/lectures/" + id;
    }

    @PostMapping("/lectures/{id}/language")
    public String updateLanguage(@PathVariable String id,
            @RequestParam(name = "languageMode", defaultValue = "AUTO") String languageMode,
            @RequestParam(name = "translationMode", defaultValue = "TRANSLATE_TO_RUSSIAN")
            String translationMode,
            @RequestParam(name = "autoTranslate", defaultValue = "false") boolean autoTranslate,
            RedirectAttributes redirect) {
        lectureService.getRequired(id);
        languages.update(id, com.lectureprocessor.multilingual.LanguageMode.from(languageMode),
                com.lectureprocessor.multilingual.TranslationMode.from(translationMode),
                autoTranslate, "ru");
        redirect.addFlashAttribute("message",
                "Языковые настройки сохранены. Запустите обработку заново, чтобы применить язык.");
        return "redirect:/lectures/" + id;
    }

    @PostMapping("/lectures/{id}/detected-language")
    public String correctDetectedLanguage(@PathVariable String id,
            @RequestParam("language") String language, RedirectAttributes redirect) {
        lectureService.getRequired(id);
        languages.correctDetected(id, language);
        redirect.addFlashAttribute("message",
                "Определённый язык исправлен; перевод будет пересчитан при resume.");
        return "redirect:/lectures/" + id;
    }

    @PostMapping("/lectures/{id}/process")
    public String process(@PathVariable String id) {
        lectureService.getRequired(id);
        jobService.submit(id);
        return "redirect:/lectures/" + id;
    }

    @PostMapping("/lectures/{id}/resume")
    public String resume(@PathVariable String id) {
        lectureService.getRequired(id);
        jobService.submit(id);
        return "redirect:/lectures/" + id;
    }

    @PostMapping("/lectures/{id}/cancel")
    public String cancel(@PathVariable String id, RedirectAttributes redirect) {
        lectureService.getRequired(id);
        boolean cancelled = jobService.cancel(id);
        if (cancelled) {
            redirect.addFlashAttribute("message",
                    "Отмена запрошена. Текущий этап остановится, статус станет «Отменено».");
        } else {
            redirect.addFlashAttribute("message", "Задача уже не выполняется.");
        }
        return "redirect:/lectures/" + id;
    }

    @PostMapping("/lectures/{id}/delete")
    public String delete(@PathVariable String id) {
        lectureService.delete(id);
        return "redirect:/";
    }

    @GetMapping("/lectures/{id}/status")
    public String status(@PathVariable String id, Model model) {
        model.addAttribute("lecture", lectureService.getRequired(id));
        model.addAttribute("job", jobStatusService.view(id));
        return "fragments/status :: status";
    }

    @GetMapping("/lectures/{id}/chat")
    public String chat(@PathVariable String id, Model model) {
        Lecture lecture = lectureService.getRequired(id);
        String sessionId = UUID.randomUUID().toString();
        model.addAttribute("lecture", lecture);
        model.addAttribute("sessionId", sessionId);
        model.addAttribute("history", List.of());
        return "chat";
    }

    @PostMapping("/lectures/{id}/chat")
    public String ask(@PathVariable String id, @RequestParam("session") String session,
            @RequestParam("question") String question, Model model) {
        model.addAttribute("lecture", lectureService.getRequired(id));
        model.addAttribute("sessionId", session);
        model.addAttribute("answer", chatService.ask(id, session, question));
        return "fragments/chat :: answer";
    }

    @PostMapping("/lectures/{id}/chat/clear")
    public String clearChat(@PathVariable String id, @RequestParam("session") String session) {
        chatService.clear(id, session);
        return "redirect:/lectures/" + id + "/chat";
    }

    @GetMapping("/benchmarks")
    public String benchmarks(Model model) {
        model.addAttribute("config", benchmarkService.loadDefaultConfig());
        model.addAttribute("lectures", lectureService.findAll());
        model.addAttribute("providers", benchmarkRuns.providers());
        model.addAttribute("prompts", benchmarkRuns.promptTemplates());
        model.addAttribute("presets", benchmarkRuns.presets());
        model.addAttribute("settings", benchmarkRuns.currentSettings());
        model.addAttribute("history", benchmarkRuns.history(25));
        model.addAttribute("recommended", benchmarkRuns.recommended().orElse(null));
        model.addAttribute("usage", benchmarkUsage.summary());
        return "benchmarks";
    }

    @GetMapping("/benchmarks/history")
    public String benchmarkHistory(Model model) {
        model.addAttribute("history", benchmarkRuns.history(25));
        model.addAttribute("recommended", benchmarkRuns.recommended().orElse(null));
        return "fragments/benchmark-history :: history";
    }

    @PostMapping("/benchmarks/run")
    public String runBenchmark(@RequestParam("lectureId") String lectureId,
            @RequestParam(name = "fixtureKey", required = false) String fixtureKey,
            @RequestParam(name = "provider", defaultValue = "ollama") String provider,
            @RequestParam("model") String model,
            @RequestParam(name = "temperature", defaultValue = "0.1") double temperature,
            @RequestParam(name = "maxTokens", defaultValue = "1536") int maxTokens,
            @RequestParam(name = "contextWindow", defaultValue = "4096") int contextWindow,
            @RequestParam(name = "thinking", defaultValue = "false") boolean thinking,
            @RequestParam("promptTemplate") String promptTemplate,
            RedirectAttributes redirect) {
        try {
            LlmRunConfig config = new LlmRunConfig(provider, model, temperature, maxTokens,
                    contextWindow, thinking, promptTemplate);
            var run = benchmarkRuns.submit(new com.lectureprocessor.benchmark.BenchmarkRunRequest(
                    lectureId, fixtureKey, config, null));
            redirect.addFlashAttribute("message", "Benchmark #" + run.id() + " поставлен в очередь");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/benchmarks";
    }

    @PostMapping("/benchmarks/{id}/recommend")
    public String recommendBenchmark(@PathVariable long id, RedirectAttributes redirect) {
        try {
            benchmarkRuns.recommend(id);
            redirect.addFlashAttribute("message", "Конфигурация из run #" + id + " сохранена как recommended");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/benchmarks";
    }

    @GetMapping("/benchmarks/compare")
    public String compareBenchmarks(@RequestParam(name = "selected", required = false) List<Long> selected,
            Model model) {
        if (selected != null && selected.size() >= 2) {
            model.addAttribute("comparison", benchmarkRuns.compare(selected.get(0), selected.get(1)));
        }
        return "fragments/benchmark-compare :: comparison";
    }
}
