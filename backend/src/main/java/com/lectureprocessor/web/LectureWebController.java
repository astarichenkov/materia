package com.lectureprocessor.web;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.lectureprocessor.benchmark.BenchmarkRunService;
import com.lectureprocessor.benchmark.BenchmarkService;
import com.lectureprocessor.benchmark.BenchmarkUsageService;
import com.lectureprocessor.chat.LectureChatService;
import com.lectureprocessor.discovery.LocalSimilarityService;
import com.lectureprocessor.discovery.VideoDiscoveryService;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.download.MediaQuality;
import com.lectureprocessor.job.LectureJobService;
import com.lectureprocessor.lecture.InvalidUploadException;
import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.lecture.RemoteLectureService;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmSettingsService;
import com.lectureprocessor.multilingual.LectureLanguageService;
import com.lectureprocessor.pipeline.JobStatusService;
import com.lectureprocessor.repository.SegmentRepository;

/**
 * Minimal Thymeleaf + HTMX UI: upload, monitor status, download results, chat
 * per lecture and view benchmark config.
 */
@Controller
public class LectureWebController {

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

    public LectureWebController(LectureService lectureService, RemoteLectureService remoteLectureService,
            LectureJobService jobService, JobStatusService jobStatusService,
            LectureChatService chatService, BenchmarkService benchmarkService,
            BenchmarkRunService benchmarkRuns, BenchmarkUsageService benchmarkUsage,
            LlmSettingsService llmSettings, LectureLanguageService languages,
            LocalSimilarityService similarities, VideoDiscoveryService discovery,
            SegmentRepository segments) {
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
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("lectures", lectureService.findAll());
        return "index";
    }

    @PostMapping("/lectures")
    public String upload(@RequestParam("file") MultipartFile file,
            @RequestParam(name = "clean", defaultValue = "false") boolean clean,
            @RequestParam(name = "slides", defaultValue = "false") boolean slides,
            @RequestParam(name = "languageMode", defaultValue = "AUTO") String languageMode,
            @RequestParam(name = "translationMode", defaultValue = "TRANSLATE_TO_RUSSIAN")
            String translationMode,
            @RequestParam(name = "autoTranslate", defaultValue = "true") boolean autoTranslate,
            RedirectAttributes redirect) {
        try {
            Lecture lecture = lectureService.create(file, clean, slides, languageMode,
                    translationMode, autoTranslate, "ru");
            jobService.submit(lecture.id());
            redirect.addFlashAttribute("message", "Лекция загружена, обработка запущена");
            return "redirect:/lectures/" + lecture.id();
        } catch (InvalidUploadException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        }
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
            RedirectAttributes redirect) {
        try {
            Lecture lecture = remoteLectureService.createFromUrl(url, quality, audioOnly, clean,
                    slides, languageMode, translationMode, autoTranslate, "ru");
            jobService.submit(lecture.id());
            redirect.addFlashAttribute("message", "Видео принято в обработку");
            return "redirect:/lectures/" + lecture.id();
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        }
    }

    @GetMapping("/lectures/preview")
    public String preview(@RequestParam("url") String url, Model model) {
        try {
            model.addAttribute("metadata", remoteLectureService.preview(url));
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
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
        try {
            model.addAttribute("similarLectures", similarities.similar(id));
        } catch (RuntimeException e) {
            model.addAttribute("similarLectures", java.util.List.of());
        }
        model.addAttribute("discoveryProviders", discovery.providerStatuses());
        model.addAttribute("transcriptSegments", segments.findByLecture(id));
        return "lecture";
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
    public String cancel(@PathVariable String id) {
        lectureService.getRequired(id);
        jobService.cancel(id);
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
