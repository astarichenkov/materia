package com.lectureprocessor.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.lectureprocessor.job.LectureJobService;
import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.multilingual.LanguageMode;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageService;
import com.lectureprocessor.multilingual.TranslationMode;

/** Language metadata and translation status API. */
@RestController
@RequestMapping("/api/lectures/{id}")
public class LectureLanguageApiController {

    private final LectureService lectures;
    private final LectureLanguageService languages;
    private final LectureJobService jobs;

    public LectureLanguageApiController(LectureService lectures, LectureLanguageService languages,
            LectureJobService jobs) {
        this.lectures = lectures;
        this.languages = languages;
        this.jobs = jobs;
    }

    @GetMapping("/language")
    public LectureLanguage language(@PathVariable String id) {
        lectures.getRequired(id);
        return languages.get(id);
    }

    @PostMapping("/language")
    public LectureLanguage update(@PathVariable String id, @RequestBody LanguageUpdateRequest request) {
        lectures.getRequired(id);
        return languages.update(id, LanguageMode.from(request.languageMode()),
                TranslationMode.from(request.translationMode()),
                request.autoTranslateOrDefault(), request.targetLanguageOrNull());
    }

    @PostMapping("/language/detected")
    public LectureLanguage correctDetected(@PathVariable String id,
            @RequestBody DetectedLanguageRequest request) {
        lectures.getRequired(id);
        return languages.correctDetected(id, request.language());
    }

    @GetMapping("/translation")
    public LectureLanguageService.TranslationView translation(@PathVariable String id) {
        lectures.getRequired(id);
        return languages.view(id);
    }

    @PostMapping("/translation/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> retry(@PathVariable String id) {
        lectures.getRequired(id);
        boolean submitted = jobs.submit(id);
        return Map.of("submitted", submitted, "lectureId", id);
    }

    public record LanguageUpdateRequest(String languageMode, String translationMode,
            Boolean autoTranslate, String targetLanguage) {

        public boolean autoTranslateOrDefault() {
            return autoTranslate == null || autoTranslate;
        }

        public String targetLanguageOrNull() {
            return targetLanguage == null || targetLanguage.isBlank() ? null : targetLanguage;
        }
    }

    public record DetectedLanguageRequest(String language) {
    }
}
