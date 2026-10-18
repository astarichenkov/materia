package com.lectureprocessor.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.media.MediaMetadata;
import com.lectureprocessor.process.CancellationToken;
import com.lectureprocessor.process.ProcessCancelledException;
import com.lectureprocessor.process.ProcessExecutionException;
import com.lectureprocessor.process.ProcessResult;
import com.lectureprocessor.process.StreamingProcessExecutor;
import com.lectureprocessor.util.FileChecksum;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real yt-dlp adapter. Metadata and download both use a strict argument list,
 * machine-readable output and no shell. Downloads are staged inside the lecture
 * workspace and bounded by size/time quotas.
 */
@Service
public class YtDlpVideoDownloader implements VideoDownloader, VideoMetadataService {

    private static final Logger log = LoggerFactory.getLogger(YtDlpVideoDownloader.class);
    private static final int METADATA_BUFFER_CHARS = 16 * 1024 * 1024;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final Duration[] RETRY_BACKOFF = {Duration.ofSeconds(2), Duration.ofSeconds(5)};
    private static final List<String> VIDEO_EXTENSIONS =
            List.of(".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v");
    private static final List<String> AUDIO_EXTENSIONS =
            List.of(".m4a", ".mp3", ".opus", ".ogg", ".wav", ".aac", ".flac", ".webm");

    private final StreamingProcessExecutor executor;
    private final AppProperties properties;
    private final UrlSafetyValidator validator;
    private final ObjectMapper objectMapper;

    public YtDlpVideoDownloader(StreamingProcessExecutor executor, AppProperties properties,
            UrlSafetyValidator validator, ObjectMapper objectMapper) {
        this.executor = executor;
        this.properties = properties;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override
    public MediaMetadata probe(String url, CancellationToken cancellation) {
        validator.validate(url);
        List<String> command = new ArrayList<>(List.of(
                properties.downloader().ytDlpPath(),
                "--ignore-config",
                "--no-warnings",
                "--no-playlist",
                "--socket-timeout", "30",
                "--dump-single-json",
                url));
        try {
            ProcessResult result = executor.execute(command,
                    properties.downloader().metadataTimeout(), null, cancellation, null, null,
                    METADATA_BUFFER_CHARS);
            if (!result.isSuccess()) {
                throw new MediaDownloadException("yt-dlp не смог получить метаданные: "
                        + result.outputForError());
            }
            return parseMetadata(result.stdout(), url);
        } catch (ProcessCancelledException e) {
            throw e;
        } catch (ProcessExecutionException e) {
            throw new MediaDownloadException("Не удалось получить метаданные: " + e.getMessage(), e);
        }
    }

    MediaMetadata parseMetadata(String json, String originalUrl) {
        JsonNode root = objectMapper.readTree(json);
        List<MediaMetadata.MediaFormat> formats = new ArrayList<>();
        JsonNode formatsNode = root.get("formats");
        if (formatsNode != null && formatsNode.isArray()) {
            for (JsonNode f : formatsNode) {
                formats.add(new MediaMetadata.MediaFormat(
                        text(f, "format_id"),
                        text(f, "ext"),
                        integer(f, "height"),
                        integer(f, "width"),
                        longValue(f, "filesize") != null ? longValue(f, "filesize")
                                : longValue(f, "filesize_approx"),
                        text(f, "vcodec"),
                        text(f, "acodec"),
                        doubleValue(f, "abr")));
            }
        }
        return new MediaMetadata(
                text(root, "title"),
                firstNonBlank(text(root, "extractor_key"), text(root, "extractor")),
                text(root, "id"),
                firstNonBlank(text(root, "uploader"), text(root, "channel")),
                root.hasNonNull("duration") ? Math.round(root.get("duration").asDouble() * 1000) : null,
                integer(root, "width") == null ? 0 : integer(root, "width"),
                integer(root, "height") == null ? 0 : integer(root, "height"),
                estimatedBytes(root),
                originalUrl,
                formats,
                json);
    }

    @Override
    public DownloadResult download(DownloadRequest request, CancellationToken cancellation,
            Consumer<DownloadProgress> progressConsumer) {
        validator.validate(request.url());
        MediaDownloadException lastError = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            cancellation.throwIfCancelled();
            try {
                return downloadOnce(request, cancellation, progressConsumer);
            } catch (ProcessCancelledException e) {
                throw e;
            } catch (MediaDownloadException e) {
                lastError = e;
                log.warn("Download attempt {}/{} for lecture {} failed: {}",
                        attempt, MAX_DOWNLOAD_ATTEMPTS, request.lectureId(), e.getMessage());
                cleanupPartials(request.targetDir());
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    sleep(RETRY_BACKOFF[attempt - 1], cancellation);
                }
            }
        }
        throw lastError != null ? lastError : new MediaDownloadException("Скачивание не удалось");
    }

    private DownloadResult downloadOnce(DownloadRequest request, CancellationToken cancellation,
            Consumer<DownloadProgress> progressConsumer) {
        Path dir = request.targetDir();
        ensureDirectory(dir);
        checkDiskSpace(dir, request.metadata());
        cleanupStaleSources(dir);

        if (progressConsumer != null) {
            progressConsumer.accept(DownloadProgress.starting());
        }
        AtomicReference<DownloadProgress> lastProgress = new AtomicReference<>();
        // yt-dlp writes its progress bar/template to stderr (not stdout), so
        // both streams must be inspected or the UI never receives download
        // percentages for VK/YouTube/HLS downloads.
        java.util.function.Consumer<String> progressLine = line ->
                YtDlpProgressParser.parse(line).ifPresent(progress -> {
                    lastProgress.set(progress);
                    if (progressConsumer != null) {
                        progressConsumer.accept(progress);
                    }
                });
        List<String> command = buildDownloadCommand(request, dir);
        ProcessResult result;
        try {
            result = executor.execute(command, properties.downloader().downloadTimeout(), dir,
                    cancellation,
                    progressLine,
                    line -> {
                        progressLine.accept(line);
                        if (line != null && !line.isBlank()) {
                            log.debug("[yt-dlp] {}", line);
                        }
                    });
        } catch (ProcessExecutionException e) {
            throw new MediaDownloadException(e.getMessage(), e);
        }

        if (!result.isSuccess()) {
            throw new MediaDownloadException("yt-dlp завершился с ошибкой: " + result.outputForError());
        }
        Path file = locateOutput(dir, request.audioOnly())
                .orElseThrow(() -> new MediaDownloadException("yt-dlp не создал файл в " + dir));
        long size = sizeOf(file);
        if (size <= 0) {
            throw new MediaDownloadException("Скачанный файл пуст: " + file.getFileName());
        }
        if (size > properties.downloader().maxFileSizeBytes()) {
            deleteQuietly(file);
            throw new MediaDownloadException("Размер загрузки превышает лимит");
        }
        if (progressConsumer != null) {
            DownloadProgress progress = lastProgress.get();
            if (progress != null) {
                progressConsumer.accept(new DownloadProgress("FINISHED", size, size, 100.0,
                        progress.speedBps(), 0L, "Скачивание завершено"));
            }
        }
        log.info("Downloaded {} bytes from {} into lecture {}",
                size, VideoPlatform.hostOf(request.url()), request.lectureId());
        return new DownloadResult(file, size, request.metadata(), List.of());
    }

    private List<String> buildDownloadCommand(DownloadRequest request, Path dir) {
        boolean audioOnly = request.audioOnly();
        boolean ffmpegAvailable = isFfmpegAvailable();
        AppProperties.Downloader cfg = properties.downloader();
        List<String> command = new ArrayList<>(List.of(
                cfg.ytDlpPath(),
                "--ignore-config",
                "--no-warnings",
                "--no-playlist",
                "--newline",
                "--socket-timeout", "30",
                "--retries", "3",
                "--fragment-retries", "5",
                "--max-filesize", Long.toString(cfg.maxFileSizeBytes()),
                "--progress-template",
                "download:progress|%(progress.status)s|%(progress.downloaded_bytes)s"
                        + "|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s"
                        + "|%(progress.speed)s|%(progress.eta)s",
                "-f", FormatSelector.formatExpression(request.quality(), audioOnly, ffmpegAvailable),
                "-P", dir.toAbsolutePath().toString(),
                "-o", audioOnly ? "source.%(ext)s" : "source.%(ext)s"));
        if (!audioOnly && ffmpegAvailable) {
            command.add("--merge-output-format");
            command.add("mp4");
            if (shouldPassFfmpegLocation()) {
                command.add("--ffmpeg-location");
                command.add(properties.tools().ffmpegPath());
            }
        }
        command.add(request.url());
        return command;
    }

    private boolean isFfmpegAvailable() {
        String path = properties.tools().ffmpegPath();
        if (path == null || path.isBlank()) {
            return false;
        }
        Path candidate = Path.of(path);
        // Absolute/relative path configured explicitly.
        if (candidate.isAbsolute() || path.contains("/") || path.contains("\\")) {
            return Files.exists(candidate) || Files.isExecutable(candidate);
        }
        // Bare command name: the tool is available when it is on PATH.
        if (Files.exists(candidate)) {
            return true;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return false;
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                if (Files.exists(Path.of(dir, path)) || Files.exists(Path.of(dir, path + ".exe"))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // malformed PATH entry
            }
        }
        return false;
    }

    /** Only pass {@code --ffmpeg-location} for an explicit path yt-dlp cannot resolve itself. */
    private boolean shouldPassFfmpegLocation() {
        String path = properties.tools().ffmpegPath();
        if (path == null || path.isBlank()) {
            return false;
        }
        return Path.of(path).isAbsolute() || path.contains("/") || path.contains("\\");
    }

    Optional<Path> locateOutput(Path dir, boolean audioOnly) {
        // yt-dlp falls back from `bestaudio/best` to `best` for platforms that
        // do not expose an audio-only stream, so an audio-only request can
        // legitimately produce source.mp4 or source.mkv. Accept every media
        // extension; the requested mode only affects format selection.
        List<String> extensions = new ArrayList<>(AUDIO_EXTENSIONS);
        extensions.addAll(VIDEO_EXTENSIONS);
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.startsWith("source.")
                                && !name.endsWith(".part")
                                && !name.endsWith(".ytdl")
                                && extensions.stream().anyMatch(name::endsWith);
                    })
                    .max(Comparator.comparingLong(YtDlpVideoDownloader::sizeOf));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void checkDiskSpace(Path dir, MediaMetadata metadata) {
        long estimate = metadata != null && metadata.estimatedBytes() > 0
                ? metadata.estimatedBytes()
                : properties.downloader().maxFileSizeBytes();
        long required = Math.min(estimate * 2, properties.downloader().maxFileSizeBytes())
                + 200L * 1024 * 1024;
        try {
            long usable = Files.getFileStore(dir).getUsableSpace();
            if (usable < required) {
                throw new MediaDownloadException(
                        "Недостаточно места на диске для загрузки (нужно ~"
                                + (required / (1024 * 1024)) + " МБ)");
            }
        } catch (IOException e) {
            log.warn("Cannot check disk space for {}: {}", dir, e.getMessage());
        }
    }

    private void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new MediaDownloadException("Не удалось создать каталог загрузки: " + dir, e);
        }
    }

    private void cleanupStaleSources(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("source.")).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException e) {
            log.debug("Cannot clean stale sources in {}: {}", dir, e.getMessage());
        }
    }

    private void cleanupPartials(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".part") || name.endsWith(".ytdl") || name.endsWith(".temp");
            }).forEach(YtDlpVideoDownloader::deleteQuietly);
        } catch (IOException e) {
            log.debug("Cannot clean partials in {}: {}", dir, e.getMessage());
        }
    }

    private static void sleep(Duration duration, CancellationToken cancellation) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            cancellation.throwIfCancelled();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProcessCancelledException("Скачивание отменено");
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.asInt();
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.asLong();
    }

    private static Double doubleValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.asDouble();
    }

    private static long estimatedBytes(JsonNode root) {
        Long filesize = longValue(root, "filesize");
        if (filesize != null && filesize > 0) {
            return filesize;
        }
        Long approx = longValue(root, "filesize_approx");
        if (approx != null && approx > 0) {
            return approx;
        }
        long max = 0;
        JsonNode formats = root.get("formats");
        if (formats != null && formats.isArray()) {
            for (JsonNode f : formats) {
                Long size = longValue(f, "filesize");
                if (size == null) {
                    size = longValue(f, "filesize_approx");
                }
                if (size != null) {
                    max = Math.max(max, size);
                }
            }
        }
        return max;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Exposed for stages that need a checksum after download. */
    public static String checksum(Path file) {
        return FileChecksum.sha256(file);
    }
}
