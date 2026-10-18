package com.lectureprocessor.thumbnail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.thumbnail.ThumbnailRepository;

/**
 * Read-only thumbnail serving endpoint plus a bounded maintenance backfill.
 *
 * <p>The file is addressed by lecture id only: callers can never request an
 * arbitrary path, and absolute filesystem paths are never exposed.</p>
 */
@RestController
public class ThumbnailApiController {

    private final ThumbnailService thumbnails;
    private final ThumbnailRepository repository;
    private final ThumbnailJobQueue queue;
    private final LectureService lectures;

    public ThumbnailApiController(ThumbnailService thumbnails, ThumbnailRepository repository,
            ThumbnailJobQueue queue, LectureService lectures) {
        this.thumbnails = thumbnails;
        this.repository = repository;
        this.queue = queue;
        this.lectures = lectures;
    }

    @GetMapping("/api/lectures/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable String id) {
        lectures.getRequired(id);
        Path path = thumbnails.path(id);
        if (path == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Миниатюра не создана");
        }
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .contentLength(Files.size(path))
                    .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePrivate())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(Files.readAllBytes(path));
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось прочитать миниатюру");
        }
    }

    /**
     * Bounded backfill of missing thumbnails. Idempotent: READY/NO_SOURCE rows are
     * skipped and at most {@code limit} (max 50) lectures are processed.
     */
    @PostMapping("/api/library/thumbnails/backfill")
    public Map<String, Object> backfill(@RequestParam(name = "limit", defaultValue = "10") int limit) {
        int bounded = Math.max(1, Math.min(limit, 50));
        List<String> candidates = new ArrayList<>(repository.missingThumbnails(bounded));
        List<String> enqueued = new ArrayList<>();
        for (String id : candidates) {
            if (queue.enqueue(id)) {
                enqueued.add(id);
            }
        }
        return Map.of("requested", bounded, "candidates", candidates.size(),
                "enqueued", enqueued.size());
    }
}
