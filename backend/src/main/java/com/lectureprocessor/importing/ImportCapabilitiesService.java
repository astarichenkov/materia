package com.lectureprocessor.importing;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.download.MediaQuality;
import com.lectureprocessor.download.VideoPlatform;
import com.lectureprocessor.download.YtDlpOptions;
import com.lectureprocessor.util.ExecutableLocator;

/**
 * Resolves real tool availability for the import wizard.
 *
 * <p>Locating executables only touches the filesystem/PATH, but it is still a
 * per-request cost, so the result is cached for a short period. Nothing here
 * starts a process or reveals configured paths/secrets.</p>
 */
@Service
public class ImportCapabilitiesService {

    /** Extensions accepted by {@code LectureService}; kept in sync explicitly. */
    static final List<String> FILE_EXTENSIONS =
            List.of(".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v",
                    ".mp3", ".m4a", ".wav", ".flac");

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final AppProperties properties;
    private final YtDlpOptions ytDlpOptions;

    private volatile ImportCapabilities cached;
    private volatile long cachedAtNanos;

    public ImportCapabilitiesService(AppProperties properties, YtDlpOptions ytDlpOptions) {
        this.properties = properties;
        this.ytDlpOptions = ytDlpOptions;
    }

    public ImportCapabilities capabilities() {
        ImportCapabilities current = cached;
        long now = System.nanoTime();
        if (current != null && now - cachedAtNanos < CACHE_TTL.toNanos()) {
            return current;
        }
        ImportCapabilities fresh = resolve();
        cached = fresh;
        cachedAtNanos = now;
        return fresh;
    }

    private ImportCapabilities resolve() {
        boolean ytDlp = ExecutableLocator
                .locateConfigured(properties.downloader().ytDlpPath())
                .isPresent();
        List<String> platforms = Arrays.stream(VideoPlatform.values())
                .map(VideoPlatform::displayName)
                .toList();
        return new ImportCapabilities(FILE_EXTENSIONS,
                properties.upload().maxFileSizeBytes(), platforms, ytDlp,
                ytDlpOptions.ffmpegAvailable(),
                MediaQuality.fromLabel(properties.downloader().defaultQuality()).label(),
                properties.downloader().allowHttp());
    }
}
