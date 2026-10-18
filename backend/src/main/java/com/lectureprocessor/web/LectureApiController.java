package com.lectureprocessor.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.lectureprocessor.chat.LectureChatService;
import com.lectureprocessor.domain.ChatMessage;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.RagAnswer;
import com.lectureprocessor.domain.Slide;
import com.lectureprocessor.download.VideoPlatform;
import com.lectureprocessor.job.LectureJobService;
import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.lecture.LectureNotFoundException;
import com.lectureprocessor.lecture.RemoteLectureService;
import com.lectureprocessor.media.MediaMetadata;
import com.lectureprocessor.pipeline.JobStatusService;
import com.lectureprocessor.pipeline.JobStatusView;
import com.lectureprocessor.processing.OutputVariant;
import com.lectureprocessor.processing.OutputVariantService;
import com.lectureprocessor.repository.SlideRepository;
import com.lectureprocessor.storage.FileStorageService;

/**
 * REST API for lectures: upload, status, processing and artifact downloads.
 */
@RestController
@RequestMapping("/api/lectures")
public class LectureApiController {

    private final LectureService lectureService;
    private final RemoteLectureService remoteLectureService;
    private final LectureJobService jobService;
    private final JobStatusService jobStatusService;
    private final LectureChatService chatService;
    private final SlideRepository slideRepository;
    private final FileStorageService storage;
    private final OutputVariantService variants;

    public LectureApiController(LectureService lectureService, RemoteLectureService remoteLectureService,
            LectureJobService jobService, JobStatusService jobStatusService,
            LectureChatService chatService, SlideRepository slideRepository, FileStorageService storage,
            OutputVariantService variants) {
        this.lectureService = lectureService;
        this.remoteLectureService = remoteLectureService;
        this.jobService = jobService;
        this.jobStatusService = jobStatusService;
        this.chatService = chatService;
        this.slideRepository = slideRepository;
        this.storage = storage;
        this.variants = variants;
    }

    @GetMapping
    public List<Lecture> list() {
        return lectureService.findAll();
    }

    @GetMapping("/{id}")
    public Lecture get(@PathVariable String id) {
        return lectureService.getRequired(id);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Lecture upload(@RequestParam("file") MultipartFile file,
            @RequestParam(name = "clean", defaultValue = "false") boolean clean,
            @RequestParam(name = "slides", defaultValue = "false") boolean slides,
            @RequestParam(name = "languageMode", defaultValue = "AUTO") String languageMode,
            @RequestParam(name = "translationMode", defaultValue = "TRANSLATE_TO_RUSSIAN")
            String translationMode,
            @RequestParam(name = "autoTranslate", defaultValue = "true") boolean autoTranslate) {
        return lectureService.create(file, clean, slides, languageMode, translationMode,
                autoTranslate, "ru");
    }

    @PostMapping("/{id}/process")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> process(@PathVariable String id) {
        lectureService.getRequired(id);
        boolean submitted = jobService.submit(id);
        return Map.of("submitted", submitted, "lectureId", id);
    }

    @PostMapping("/{id}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> resume(@PathVariable String id) {
        lectureService.getRequired(id);
        boolean submitted = jobService.submit(id);
        return Map.of("submitted", submitted, "lectureId", id);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        lectureService.getRequired(id);
        boolean cancelled = jobService.cancel(id);
        return Map.of("cancelled", cancelled, "lectureId", id);
    }

    @GetMapping("/{id}/progress")
    public JobStatusView progress(@PathVariable String id) {
        lectureService.getRequired(id);
        return jobStatusService.view(id);
    }

    @PostMapping(value = "/from-url", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createFromUrl(@RequestBody FromUrlRequest request) {
        Lecture lecture = remoteLectureService.createFromUrl(request.url(), request.quality(),
                request.audioOnlyOrDefault(), request.cleanOrDefault(), request.slidesOrDefault(),
                request.languageModeOrDefault(), request.translationModeOrDefault(),
                request.autoTranslateOrDefault(), "ru");
        boolean submitted = jobService.submit(lecture.id());
        return Map.of("lectureId", lecture.id(), "submitted", submitted);
    }

    @GetMapping("/platforms")
    public List<Map<String, String>> platforms() {
        List<Map<String, String>> result = new java.util.ArrayList<>();
        for (VideoPlatform platform : VideoPlatform.values()) {
            result.add(Map.of("id", platform.name(), "name", platform.displayName(),
                    "hosts", String.join(", ", platform.allowedHosts())));
        }
        return result;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        lectureService.delete(id);
    }

    @GetMapping("/{id}/slides")
    public List<Slide> slides(@PathVariable String id) {
        return slideRepository.findByLecture(id);
    }

    @GetMapping("/{id}/slides/{index}/image")
    public ResponseEntity<Resource> slideImage(@PathVariable String id, @PathVariable int index) {
        List<Slide> slides = slideRepository.findByLecture(id);
        Slide slide = slides.stream().filter(s -> s.sequence() == index).findFirst()
                .orElseThrow(() -> new LectureNotFoundException(id + "/slide/" + index));
        return file(Path.of(slide.imagePath()), MediaType.IMAGE_JPEG);
    }

    @GetMapping("/{id}/chat")
    public List<ChatMessage> chatHistory(@PathVariable String id,
            @RequestParam("session") String session) {
        return chatService.history(id, session);
    }

    @PostMapping("/{id}/chat")
    public RagAnswer chat(@PathVariable String id, @RequestParam("session") String session,
            @RequestParam("question") String question) {
        return chatService.ask(id, session, question);
    }

    @DeleteMapping("/{id}/chat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearChat(@PathVariable String id, @RequestParam("session") String session) {
        chatService.clear(id, session);
    }

    @GetMapping("/metadata")
    public MediaMetadata metadata(@RequestParam("url") String url) {
        return remoteLectureService.preview(url);
    }

    @GetMapping("/{id}/variants")
    public List<OutputVariant> variants(@PathVariable String id) {
        lectureService.getRequired(id);
        return variants.list(id);
    }

    /**
     * Downloads an archived document variant. Only artifacts referenced by the
     * variant row of this lecture are served, so the endpoint cannot be used to
     * read arbitrary files.
     */
    @GetMapping("/{id}/variants/{variantId}/{artifact}")
    public ResponseEntity<Resource> variantArtifact(@PathVariable String id,
            @PathVariable long variantId, @PathVariable String artifact) {
        lectureService.getRequired(id);
        OutputVariant variant = variants.find(id, variantId)
                .orElseThrow(() -> new LectureNotFoundException(id + "/variant/" + variantId));
        Path path = variants.artifactPath(variant, artifact);
        if (path == null) {
            throw new LectureNotFoundException("Артефакт варианта не найден: " + artifact);
        }
        MediaType type = path.getFileName().toString().endsWith(".docx")
                ? MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                : MediaType.TEXT_MARKDOWN;
        return file(path, type);
    }

    @GetMapping("/{id}/download/{artifact}")
    public ResponseEntity<Resource> download(@PathVariable String id,
            @PathVariable String artifact) {
        Lecture lecture = lectureService.getRequired(id);
        String relative = switch (artifact) {
            case "transcript.json" -> "transcript.json";
            case "transcript.md" -> "transcript.md";
            case "notes.md" -> "notes.md";
            case "notes.docx" -> "notes.docx";
            case "transcript.original.md" -> "transcript.original.md";
            case "transcript.ru.md" -> "transcript.ru.md";
            case "transcript.bilingual.md" -> "transcript.bilingual.md";
            case "notes.original.docx" -> "notes.original.docx";
            case "notes.ru.docx" -> "notes.ru.docx";
            case "notes.bilingual.docx" -> "notes.bilingual.docx";
            case "translation.json" -> "translation.json";
            default -> throw new IllegalArgumentException("Неизвестный артефакт: " + artifact);
        };
        Path path = storage.resolve(lecture.id(), relative);
        if (!Files.exists(path)) {
            throw new LectureNotFoundException("Артефакт ещё не создан: " + artifact);
        }
        MediaType type = artifact.endsWith(".docx")
                ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                : artifact.endsWith(".json") ? MediaType.APPLICATION_JSON : MediaType.TEXT_MARKDOWN;
        return file(path, type);
    }

    private static ResponseEntity<Resource> file(Path path, MediaType type) {
        Resource resource = new FileSystemResource(path);
        try {
            return ResponseEntity.ok()
                    .contentType(type)
                    .contentLength(Files.size(path))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + path.getFileName() + "\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(resource);
        }
    }
}
