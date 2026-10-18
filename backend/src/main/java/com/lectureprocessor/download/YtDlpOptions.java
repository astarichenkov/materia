package com.lectureprocessor.download;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.util.ExecutableLocator;

/**
 * Single source of truth for yt-dlp invocation settings. Both the metadata
 * probe and the download use {@link #commonArguments()} so JavaScript runtime,
 * cookies and other extractor options can never drift apart.
 *
 * <p>Defaults are restricted on purpose:</p>
 * <ul>
 *   <li>cookies are disabled unless the administrator explicitly enables them;</li>
 *   <li>remote components (downloading executable EJS code from GitHub) are
 *       disabled unless explicitly configured;</li>
 *   <li>a JavaScript runtime is used only when it is actually installed.</li>
 * </ul>
 */
@Component
public class YtDlpOptions {

    private static final Logger log = LoggerFactory.getLogger(YtDlpOptions.class);

    public static final String MODE_AUTO = "auto";
    public static final String MODE_NODE = "node";
    public static final String MODE_DENO = "deno";
    public static final String MODE_DISABLED = "disabled";

    public static final String COOKIES_NONE = "none";
    public static final String COOKIES_BROWSER = "browser";
    public static final String COOKIES_FILE = "file";

    private final AppProperties properties;

    public YtDlpOptions(AppProperties properties) {
        this.properties = properties;
    }

    /** Executable plus extractor options shared by probe and download. */
    public List<String> commonArguments() {
        AppProperties.Downloader cfg = downloader();
        List<String> args = new ArrayList<>();
        args.add(cfg.ytDlpPath());
        args.add("--ignore-config");
        args.add("--no-playlist");
        args.add("--socket-timeout");
        args.add("30");
        jsRuntime().ifPresent(spec -> {
            args.add("--js-runtimes");
            args.add(spec);
        });
        remoteComponents().ifPresent(spec -> {
            args.add("--remote-components");
            args.add(spec);
        });
        cookiesArguments().forEach(args::add);
        return args;
    }

    /**
     * Resolves the JavaScript runtime spec accepted by {@code --js-runtimes}
     * ({@code name} or {@code name:path}). Empty means "let yt-dlp use its own
     * defaults" (currently Deno only).
     */
    public Optional<String> jsRuntime() {
        String mode = trimmed(downloader().jsRuntime()).toLowerCase(Locale.ROOT);
        if (mode.isEmpty() || MODE_DISABLED.equals(mode) || "none".equals(mode)) {
            return Optional.empty();
        }
        String explicitPath = trimmed(downloader().jsRuntimePath());
        if (MODE_NODE.equals(mode) || MODE_DENO.equals(mode)) {
            return resolveRuntime(mode, explicitPath)
                    .map(path -> mode + ":" + path);
        }
        if (!MODE_AUTO.equals(mode)) {
            log.warn("Unknown yt-dlp js-runtime '{}'; expected auto/node/deno/disabled", mode);
            return Optional.empty();
        }
        for (String candidate : List.of(MODE_NODE, MODE_DENO)) {
            Optional<Path> resolved = resolveRuntime(candidate, explicitPath);
            if (resolved.isPresent()) {
                return Optional.of(candidate + ":" + resolved.get());
            }
        }
        log.debug("No JavaScript runtime (node/deno) found; yt-dlp runs without one");
        return Optional.empty();
    }

    /** Explicit opt-in only: never fetch executable components by default. */
    public Optional<String> remoteComponents() {
        String spec = trimmed(downloader().remoteComponents());
        if (spec.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(spec);
    }

    public List<String> cookiesArguments() {
        String mode = trimmed(downloader().cookiesMode()).toLowerCase(Locale.ROOT);
        if (mode.isEmpty() || COOKIES_NONE.equals(mode)) {
            return List.of();
        }
        if (COOKIES_BROWSER.equals(mode)) {
            String browser = trimmed(downloader().cookiesBrowser());
            if (browser.isEmpty()) {
                throw new MediaDownloadException(
                        "Режим cookies=browser включён, но YTDLP_COOKIES_BROWSER не задан. "
                                + "Укажите браузер (chrome, edge, firefox) или отключите cookies.",
                        false, null);
            }
            String profile = trimmed(downloader().cookiesBrowserProfile());
            String spec = profile.isEmpty() ? browser : browser + ":" + profile;
            return List.of("--cookies-from-browser", spec);
        }
        if (COOKIES_FILE.equals(mode)) {
            String file = trimmed(downloader().cookiesFile());
            if (file.isEmpty() || !Files.isRegularFile(Path.of(file))) {
                throw new MediaDownloadException(
                        "Режим cookies=file включён, но файл cookies не найден: "
                                + (file.isEmpty() ? "YTDLP_COOKIES_FILE не задан" : file),
                        false, null);
            }
            return List.of("--cookies", file);
        }
        throw new MediaDownloadException(
                "Неизвестный режим cookies '" + mode + "'; ожидается none, browser или file.",
                false, null);
    }

    /** Whether FFmpeg is actually reachable from this process. */
    public boolean ffmpegAvailable() {
        return ffmpegBinary().isPresent();
    }

    /**
     * Absolute FFmpeg path passed via {@code --ffmpeg-location} when the
     * configured value is a path (file or bin directory). For a bare command
     * name that is already on PATH yt-dlp resolves it itself, so no argument is
     * added.
     */
    public String ffmpegLocation() {
        String configured = trimmed(properties.tools().ffmpegPath());
        if (configured.isEmpty()) {
            return null;
        }
        boolean explicit;
        try {
            explicit = Path.of(configured).isAbsolute()
                    || configured.contains("/") || configured.contains("\\");
        } catch (RuntimeException e) {
            return null;
        }
        if (!explicit) {
            return null;
        }
        return ffmpegBinary().map(Path::toString).orElse(null);
    }

    public Optional<Path> ffmpegBinary() {
        return ExecutableLocator.locateConfigured(properties.tools().ffmpegPath());
    }

    /** Human-readable summary for diagnostics; never contains cookie contents. */
    public String describe() {
        AppProperties.Downloader cfg = downloader();
        String js = jsRuntime().orElse("disabled");
        String cookies = trimmed(cfg.cookiesMode()).isEmpty() ? COOKIES_NONE : trimmed(cfg.cookiesMode());
        String ffmpeg = ffmpegBinary().map(Path::toString).orElse("не найден");
        return "js-runtime=" + js + ", cookies=" + cookies + ", ffmpeg=" + ffmpeg
                + ", remote-components=" + (remoteComponents().isPresent() ? "enabled" : "disabled");
    }

    private Optional<Path> resolveRuntime(String runtime, String explicitPath) {
        return ExecutableLocator.locate(runtime, explicitPath);
    }

    private AppProperties.Downloader downloader() {
        return properties.downloader();
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.strip();
    }
}
