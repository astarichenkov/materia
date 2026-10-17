package com.lectureprocessor.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.lectureprocessor.discovery.LocalSimilarityService;
import com.lectureprocessor.discovery.VideoDiscoveryService;
import com.lectureprocessor.lecture.LectureService;

/**
 * Thymeleaf discovery page. Local recommendations and provider status are real
 * data; an online search is executed only when the user submits a query or
 * chooses a lecture.
 */
@Controller
public class DiscoveryWebController {

    private final LocalSimilarityService similarities;
    private final VideoDiscoveryService discovery;
    private final LectureService lectures;

    public DiscoveryWebController(LocalSimilarityService similarities,
            VideoDiscoveryService discovery, LectureService lectures) {
        this.similarities = similarities;
        this.discovery = discovery;
        this.lectures = lectures;
    }

    @GetMapping("/discover")
    public String page(@RequestParam(name = "lectureId", required = false) String lectureId,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "language", required = false) String language,
            @RequestParam(name = "minDuration", required = false) Integer minDuration,
            @RequestParam(name = "maxDuration", required = false) Integer maxDuration,
            @RequestParam(name = "maxResults", required = false) Integer maxResults,
            Model model) {
        model.addAttribute("lectures", lectures.findAll());
        model.addAttribute("providers", discovery.providerStatuses());
        model.addAttribute("recent", discovery.recent());
        model.addAttribute("selectedLectureId", lectureId);
        model.addAttribute("query", query);
        model.addAttribute("platform", platform);
        model.addAttribute("language", language);
        model.addAttribute("minDuration", minDuration);
        model.addAttribute("maxDuration", maxDuration);
        model.addAttribute("maxResults", maxResults);
        if (lectureId != null && !lectureId.isBlank()) {
            try {
                model.addAttribute("similarLectures", similarities.similar(lectureId));
            } catch (RuntimeException e) {
                model.addAttribute("similarError", e.getMessage());
            }
        }
        boolean shouldSearch = (query != null && !query.isBlank())
                || (lectureId != null && !lectureId.isBlank());
        if (shouldSearch) {
            try {
                model.addAttribute("search", discovery.search(
                        new VideoDiscoveryService.DiscoverySearchRequest(lectureId, query, platform,
                                language, minDuration, maxDuration, null, maxResults)));
            } catch (RuntimeException e) {
                model.addAttribute("searchError", e.getMessage());
            }
        }
        return "discover";
    }

    @PostMapping("/discover/import")
    public String importVideo(@RequestParam("provider") String provider,
            @RequestParam(name = "externalId", required = false) String externalId,
            @RequestParam("url") String url,
            @RequestParam(name = "clean", defaultValue = "false") boolean clean,
            @RequestParam(name = "slides", defaultValue = "false") boolean slides,
            RedirectAttributes redirect) {
        try {
            VideoDiscoveryService.ImportResult result = discovery.importVideo(
                    new VideoDiscoveryService.ImportRequest(provider, externalId, url, "720p",
                            clean, slides));
            redirect.addFlashAttribute("message", result.created()
                    ? "Видео добавлено в библиотеку, обработка запущена"
                    : "Это видео уже есть в библиотеке");
            return "redirect:/lectures/" + result.lectureId();
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/discover";
        }
    }
}
