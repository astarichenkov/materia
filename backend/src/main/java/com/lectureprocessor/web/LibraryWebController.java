package com.lectureprocessor.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.lecture.LectureLibraryService;
import com.lectureprocessor.thumbnail.ThumbnailJobQueue;
import com.lectureprocessor.web.ui.StatusLabels;

/**
 * Read-only library view. It reuses {@link LectureLibraryService} and keeps the
 * existing query contract (`q`, `language`, `status`, `hasTranslation`, `sort`)
 * while adding bounded server-side pagination.
 */
@Controller
public class LibraryWebController {

    private static final int PAGE_SIZE = 12;

    private final LectureLibraryService library;
    private final ThumbnailJobQueue thumbnails;

    public LibraryWebController(LectureLibraryService library, ThumbnailJobQueue thumbnails) {
        this.library = library;
        this.thumbnails = thumbnails;
    }

    @GetMapping("/library")
    public String library(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "language", required = false) String language,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "hasTranslation", required = false) Boolean hasTranslation,
            @RequestParam(name = "sort", defaultValue = "date-desc") String sort,
            @RequestParam(name = "view", defaultValue = "grid") String view,
            @RequestParam(name = "page", defaultValue = "1") int page,
            Model model) {
        LectureLibraryService.LibraryPage result = library.searchPage(query, language, status,
                hasTranslation, sort, page, PAGE_SIZE);
        // Cheap, bounded and asynchronous: never runs FFmpeg inside this request.
        result.items().forEach(item -> {
            if (!item.hasThumbnail()) {
                thumbnails.enqueue(item.lectureId());
            }
        });
        boolean filtersActive = !blank(query) || !blank(language) || !blank(status)
                || hasTranslation != null;
        model.addAttribute("items", result.items());
        model.addAttribute("result", result);
        model.addAttribute("languages", library.languages());
        model.addAttribute("statuses", JobStatus.values());
        model.addAttribute("statusLabels", StatusLabels.all());
        model.addAttribute("q", query == null ? "" : query);
        model.addAttribute("language", language == null ? "" : language);
        model.addAttribute("status", status == null ? "" : status);
        model.addAttribute("hasTranslation", hasTranslation);
        model.addAttribute("sort", sort);
        model.addAttribute("view", "list".equalsIgnoreCase(view) ? "list" : "grid");
        model.addAttribute("filtersActive", filtersActive);
        model.addAttribute("anyLectures", library.countAll() > 0);
        return "library";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
