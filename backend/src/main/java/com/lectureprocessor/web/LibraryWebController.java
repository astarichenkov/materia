package com.lectureprocessor.web;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.lecture.LectureLibraryService;

/**
 * Read-only library view. It reuses {@link LectureLibraryService} and keeps the
 * existing query contract; no new search or persistence logic is introduced.
 */
@Controller
public class LibraryWebController {

    private final LectureLibraryService library;

    public LibraryWebController(LectureLibraryService library) {
        this.library = library;
    }

    @GetMapping("/library")
    public String library(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "language", required = false) String language,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "hasTranslation", required = false) Boolean hasTranslation,
            @RequestParam(name = "sort", defaultValue = "date-desc") String sort,
            Model model) {
        List<LectureLibraryService.LibraryItem> items = library.search(query, language, status,
                hasTranslation, sort);
        List<String> languages = library.search(null, null, null, null, "date-desc").stream()
                .map(LectureLibraryService.LibraryItem::detectedLanguage)
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
        model.addAttribute("items", items);
        model.addAttribute("languages", languages);
        model.addAttribute("statuses", JobStatus.values());
        model.addAttribute("q", query == null ? "" : query);
        model.addAttribute("language", language == null ? "" : language);
        model.addAttribute("status", status == null ? "" : status);
        model.addAttribute("hasTranslation", hasTranslation);
        model.addAttribute("sort", sort);
        return "library";
    }
}
