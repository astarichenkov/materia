package com.lectureprocessor.thumbnail;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.download.UnsafeMediaUrlException;
import com.lectureprocessor.download.UrlSafetyValidator;
import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.MediaSourceRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.video.FfmpegService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds and caches a poster image for a lecture.
 *
 * <p>Order of preference:</p>
 * <ol>
 *   <li>the thumbnail advertised by the remote extractor metadata (downloaded
 *       once, validated and cached locally so opening the library never calls
 *       the external platform);</li>
 *   <li>a real video frame extracted with FFmpeg. Several candidate timestamps
 *       are sampled so the first (usually black) frame is not used;</li>
 *   <li>nothing: audio-only or unreadable media is recorded as
 *       {@link ThumbnailStatus#NO_SOURCE} and the UI shows a neutral
 *       placeholder.</li>
 * </ol>
 *
 * <p>Generation is best-effort and never throws into the caller; failures are
 * persisted so a retry is possible and observable.</p>
 */
@Service
public class ThumbnailService {

    public static final String RELATIVE_PATH = "thumbnail.jpg";
    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);
    private static final List<Double> CANDIDATE_FRACTIONS =
            List.of(0.1, 0.3, 0.5, 0.7);
    private static final java.util.Set<String> AUDIO_EXTENSIONS =
            java.util.Set.of(".mp3", ".m4a", ".wav", ".flac", ".ogg", ".opus", ".aac");

    private final FfmpegService ffmpeg;
    private final FileStorageService storage;
    private final ThumbnailRepository repository;
    private final LectureRepository lectures;
    private final MediaSourceRepository mediaSources;
    private final ThumbnailProperties properties;
    private final UrlSafetyValidator urlValidator;
    private final ObjectMapper objectMapper;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public ThumbnailService(FfmpegService ffmpeg, FileStorageService storage,
            ThumbnailRepository repository, LectureRepository lectures,
            MediaSourceRepository mediaSources, ThumbnailProperties properties,
            UrlSafetyValidator urlValidator, ObjectMapper objectMapper) {
        this.ffmpeg = ffmpeg;
        this.storage = storage;
        this.repository = repository;
        this.lectures = lectures;
        this.mediaSources = mediaSources;
        this.properties = properties;
        this.urlValidator = urlValidator;
        this.objectMapper = objectMapper;
    }

    public Optional<LectureThumbnail> find(String lectureId) {
        if (lectureId == null) {
            return Optional.empty();
        }
        return repository.find(lectureId).filter(thumbnail -> {
            if (!thumbnail.servable()) {
                return false;
            }
            return Files.isRegularFile(storage.resolve(lectureId, thumbnail.relativePath()));
        });
    }

    public boolean isReady(String lectureId) {
        return find(lectureId).isPresent();
    }

    /** Absolute path of a servable cached thumbnail, or {@code null}. */
    public Path path(String lectureId) {
        return find(lectureId).map(t -> storage.resolve(lectureId, t.relativePath())).orElse(null);
    }

    /**
     * Should an automatic retry be attempted for this lecture? FAILED rows are
     * retried only after a cool-down so opening the library cannot hammer a
     * broken file.
     */
    public boolean needsGeneration(String lectureId) {
        Optional<LectureThumbnail> current = repository.find(lectureId);
        if (current.isEmpty()) {
            return true;
        }
        LectureThumbnail thumbnail = current.get();
        if (thumbnail.servable() && isReady(lectureId)) {
            return false;
        }
        return switch (thumbnail.status()) {
            case MISSING -> true;
            case FAILED -> thumbnail.updatedAt()
                    .plus(properties.failedRetryAfter())
                    .isBefore(Instant.now());
            case NO_SOURCE -> false;
            case READY -> true;
        };
    }

    /**
     * Synchronous generation. Never throws: all failures are logged and stored
     * in the cache row.
     */
    public Optional<LectureThumbnail> generate(String lectureId) {
        if (!properties.enabled() || lectureId == null) {
            return Optional.empty();
        }
        Object lock = locks.computeIfAbsent(lectureId, key -> new Object());
        synchronized (lock) {
            Optional<LectureThumbnail> existing = find(lectureId);
            if (existing.isPresent()) {
                return existing;
            }
            try {
                return build(lectureId);
            } catch (RuntimeException e) {
                log.warn("Thumbnail generation failed for {}: {}", lectureId, e.getMessage());
                LectureThumbnail failed = LectureThumbnail.failed(lectureId, "NONE",
                        truncate(e.getMessage(), 500));
                repository.save(failed);
                return Optional.of(failed);
            } finally {
                locks.remove(lectureId, lock);
            }
        }
    }

    /** Best-effort variant used by the pipeline; never affects the stage result. */
    public void generateQuietly(String lectureId) {
        try {
            generate(lectureId);
        } catch (RuntimeException e) {
            log.debug("Thumbnail generation skipped for {}: {}", lectureId, e.getMessage());
        }
    }

    private Optional<LectureThumbnail> build(String lectureId) {
        Lecture lecture = lectures.findById(lectureId).orElse(null);
        if (lecture == null) {
            return Optional.empty();
        }
        Path target = storage.resolve(lectureId, RELATIVE_PATH);
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // overwritten below anyway
        }
        MediaSource source = mediaSources.findByLecture(lectureId).orElse(null);

        if (source != null && source.metadataJson() != null) {
            Optional<BufferedImage> remote = remoteThumbnail(source.metadataJson());
            if (remote.isPresent()) {
                writeJpeg(remote.get(), target);
                return Optional.of(saveReady(lectureId, "REMOTE_THUMBNAIL", target));
            }
        }

        Path video = mediaPath(lecture);
        if (video == null || !Files.isRegularFile(video) || isAudioOnly(lecture, source, video)) {
            repository.save(LectureThumbnail.noSource(lectureId));
            return repository.find(lectureId);
        }
        Optional<BufferedImage> frame = videoFrame(video, lecture, lectureId);
        if (frame.isEmpty()) {
            LectureThumbnail failed = LectureThumbnail.failed(lectureId, "VIDEO_FRAME",
                    "FFmpeg не смог извлечь кадр из видео");
            repository.save(failed);
            return Optional.of(failed);
        }
        writeJpeg(frame.get(), target);
        return Optional.of(saveReady(lectureId, "VIDEO_FRAME", target));
    }

    private LectureThumbnail saveReady(String lectureId, String source, Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = 0;
        }
        LectureThumbnail ready = LectureThumbnail.ready(lectureId, source, RELATIVE_PATH, size);
        repository.save(ready);
        return ready;
    }

    private Path mediaPath(Lecture lecture) {
        String stored = lecture.storedFilename();
        if (stored == null || stored.isBlank()) {
            return null;
        }
        return storage.resolve(lecture.id(), stored);
    }

    private static boolean isAudioOnly(Lecture lecture, MediaSource source, Path video) {
        if (source != null && source.audioOnly()) {
            return true;
        }
        if (lecture.contentType() != null && lecture.contentType().startsWith("audio/")) {
            return true;
        }
        String name = video.getFileName().toString().toLowerCase(Locale.ROOT);
        return AUDIO_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private Optional<BufferedImage> remoteThumbnail(String metadataJson) {
        String url;
        try {
            JsonNode root = objectMapper.readTree(metadataJson);
            JsonNode node = root.get("thumbnail");
            if (node == null || !node.isString()) {
                return Optional.empty();
            }
            url = node.asString();
        } catch (RuntimeException e) {
            log.debug("Cannot read thumbnail URL from metadata: {}", e.getMessage());
            return Optional.empty();
        }
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = urlValidator.validateResourceUrl(url);
            byte[] bytes = download(uri);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            return Optional.ofNullable(image);
        } catch (UnsafeMediaUrlException e) {
            log.info("Remote thumbnail rejected: {}", e.getMessage());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.info("Remote thumbnail download failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private byte[] download(URI uri) throws IOException, InterruptedException {
        Duration timeout = properties.remoteTimeout() == null
                ? Duration.ofSeconds(20) : properties.remoteTimeout();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "lecture-processor/thumbnail-cache")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (!contentType.isBlank() && !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new IOException("Неожиданный content-type: " + contentType);
        }
        long limit = Math.max(1024L, properties.maxRemoteBytes());
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE - 8));
            if (bytes.length > limit) {
                throw new IOException("Изображение больше допустимого размера");
            }
            return bytes;
        }
    }

    private Optional<BufferedImage> videoFrame(Path video, Lecture lecture, String lectureId) {
        long durationMs = lecture.durationMs() == null ? 0 : lecture.durationMs();
        if (durationMs <= 0) {
            durationMs = ffmpeg.probeDurationMs(video);
        }
        List<Long> timestamps = new ArrayList<>();
        if (durationMs > 0) {
            for (double fraction : CANDIDATE_FRACTIONS) {
                timestamps.add(Math.max(0L, Math.round(durationMs * fraction)));
            }
        } else {
            timestamps.addAll(List.of(1_000L, 5_000L, 15_000L));
        }
        Path work = storage.lectureDir(lectureId).resolve("thumb-candidates");
        List<ScoredFrame> candidates = new ArrayList<>();
        try {
            Files.createDirectories(work);
            int index = 0;
            for (long timestamp : timestamps) {
                Path candidate = work.resolve("candidate-" + (index++) + ".jpg");
                Path extracted = ffmpeg.extractThumbnailFrame(video, candidate, timestamp,
                        properties.width());
                if (extracted == null) {
                    continue;
                }
                BufferedImage image = ImageIO.read(extracted.toFile());
                if (image == null) {
                    continue;
                }
                candidates.add(new ScoredFrame(extracted, score(image)));
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Thumbnail frame extraction failed for {}: {}", lectureId, e.getMessage());
        }
        Optional<BufferedImage> result = candidates.stream()
                .max(Comparator.comparingDouble(ScoredFrame::score))
                .map(frame -> readQuietly(frame.path()));
        for (ScoredFrame candidate : candidates) {
            deleteQuietly(candidate.path());
        }
        deleteQuietly(work);
        return result;
    }

    /**
     * Informativeness heuristic: pixel variance rewards detailed slides, while a
     * mean brightness outside the useful range (all black / all white) is
     * penalised. No LLM and no image model is involved.
     */
    static double score(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return -1;
        }
        long step = Math.max(1, Math.max(width, height) / 160L);
        double sum = 0;
        double sumSquares = 0;
        long count = 0;
        for (int y = 0; y < height; y += (int) step) {
            for (int x = 0; x < width; x += (int) step) {
                int rgb = image.getRGB(x, y);
                int luminance = (int) Math.round(0.299 * ((rgb >> 16) & 0xFF)
                        + 0.587 * ((rgb >> 8) & 0xFF)
                        + 0.114 * (rgb & 0xFF));
                sum += luminance;
                sumSquares += (double) luminance * luminance;
                count++;
            }
        }
        if (count == 0) {
            return -1;
        }
        double mean = sum / count;
        double variance = Math.max(0, sumSquares / count - mean * mean);
        double penalty = 0;
        if (mean < 20) {
            penalty = (20 - mean) * 6;
        } else if (mean > 240) {
            penalty = (mean - 240) * 6;
        }
        return Math.sqrt(variance) - penalty;
    }

    private BufferedImage readQuietly(Path path) {
        try {
            return ImageIO.read(path.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    private void writeJpeg(BufferedImage source, Path target) {
        BufferedImage scaled = scale(source, properties.width());
        try {
            Files.createDirectories(target.getParent());
            if (!ImageIO.write(scaled, "jpg", target.toFile())) {
                throw new IOException("ImageIO не поддерживает JPEG");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить миниатюру", e);
        }
    }

    static BufferedImage scale(BufferedImage source, int targetWidth) {
        int width = Math.max(16, targetWidth);
        if (source.getWidth() <= width) {
            width = source.getWidth();
        }
        int height = Math.max(1, Math.round(source.getHeight() * (width
                / (float) source.getWidth())));
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            // AREA_AVERAGING avoids the "flat background" degradation that broke
            // perceptual hashing for real ICC JPEGs.
            java.awt.Image scaledImage = source.getScaledInstance(width, height,
                    java.awt.Image.SCALE_AREA_AVERAGING);
            graphics.drawImage(scaledImage, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static void deleteQuietly(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.forEach(ThumbnailService::deleteQuietly);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record ScoredFrame(Path path, double score) {
    }
}
