package com.lectureprocessor.importing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.discovery.LocalSimilarityService;
import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.MediaSourceRepository;

/**
 * Minimal, backwards-compatible double-submit protection for the import wizard.
 *
 * <p>Two independent guards are provided:</p>
 * <ol>
 *   <li><b>Request id</b> — the wizard renders one id per page instance and sends
 *       it with the form. Reusing the same id returns the already created
 *       lecture instead of creating a second one (browser retry, double click,
 *       back-navigation).</li>
 *   <li><b>Canonical URL</b> — a remote URL that is already present in
 *       {@code media_source} resolves to the existing lecture, exactly like the
 *       Discovery import does.</li>
 * </ol>
 *
 * <p>Limitations (documented deliberately):</p>
 * <ul>
 *   <li>the request-id registry is in memory, so it does not survive an
 *       application restart and is not shared between instances;</li>
 *   <li>local file uploads have no content checksum here, therefore a
 *       double-submit without a request id (for example a script that omits the
 *       field) can still create two lectures.</li>
 * </ul>
 */
@Service
public class ImportDeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(ImportDeduplicationService.class);
    private static final Duration REQUEST_TTL = Duration.ofMinutes(30);
    private static final int MAX_ENTRIES = 500;

    private record Entry(String lectureId, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Entry> requests = new ConcurrentHashMap<>();
    private final MediaSourceRepository mediaSources;
    private final LectureRepository lectures;

    public ImportDeduplicationService(MediaSourceRepository mediaSources,
            LectureRepository lectures) {
        this.mediaSources = mediaSources;
        this.lectures = lectures;
    }

    /** Lecture created by an earlier submission with the same request id. */
    public Optional<String> previousRequest(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        String key = requestId.strip();
        Entry entry = requests.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            requests.remove(key, entry);
            return Optional.empty();
        }
        if (lectures.findById(entry.lectureId()).isEmpty()) {
            // The user deleted the lecture; the id must not resurrect it.
            requests.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.lectureId());
    }

    /** Remembers that a request id already produced a lecture. */
    public void remember(String requestId, String lectureId) {
        if (requestId == null || requestId.isBlank() || lectureId == null || lectureId.isBlank()) {
            return;
        }
        prune();
        requests.put(requestId.strip(),
                new Entry(lectureId, Instant.now().plus(REQUEST_TTL)));
    }

    /**
     * Existing lecture for the same remote URL. Only lectures that still exist
     * are returned; a failed match must never hide a missing lecture.
     */
    public Optional<String> existingLectureForUrl(String url) {
        String canonical = LocalSimilarityService.canonical(url);
        if (canonical.isBlank()) {
            return Optional.empty();
        }
        List<MediaSource> sources;
        try {
            sources = mediaSources.findAll();
        } catch (RuntimeException e) {
            log.warn("Duplicate URL check unavailable: {}", e.getMessage());
            return Optional.empty();
        }
        return sources.stream()
                .filter(source -> source.originalUrl() != null
                        && canonical.equals(LocalSimilarityService.canonical(source.originalUrl())))
                .map(MediaSource::lectureId)
                .filter(lectureId -> lectures.findById(lectureId).isPresent())
                .findFirst();
    }

    private void prune() {
        if (requests.size() < MAX_ENTRIES) {
            return;
        }
        Instant now = Instant.now();
        requests.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }
}
