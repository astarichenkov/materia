package com.lectureprocessor.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves an external tool to an executable file. External tools (yt-dlp,
 * node, ffmpeg) may be configured as a bare command name, an absolute path to
 * the binary, or a path to the directory that contains it. The JVM's PATH can
 * differ from the interactive shell's PATH, so the resolved absolute path is
 * what callers should pass to child processes.
 */
public final class ExecutableLocator {

    private ExecutableLocator() {
    }

    /**
     * @param name           bare command name without extension (e.g. {@code node})
     * @param configuredPath explicit path to the binary or its directory; may be blank
     * @return an existing regular file, or empty when the tool cannot be found
     */
    public static Optional<Path> locate(String name, String configuredPath) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String configured = configuredPath == null ? "" : configuredPath.strip();
        if (!configured.isEmpty()) {
            Optional<Path> explicit = fromExplicitPath(name, configured);
            if (explicit.isPresent()) {
                return explicit;
            }
            // An explicitly configured path that does not exist is a
            // configuration error, not a reason to silently fall back to PATH.
            return Optional.empty();
        }
        return searchPath(name);
    }

    /** Locates a tool using its configured value (bare name or explicit path). */
    public static Optional<Path> locateConfigured(String configured) {
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        String value = configured.strip();
        if (looksLikePath(value)) {
            return fromExplicitPath(fileNameWithoutExtension(value), value);
        }
        return searchPath(value);
    }

    private static Optional<Path> fromExplicitPath(String name, String configured) {
        Path candidate;
        try {
            candidate = Path.of(configured);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (Files.isRegularFile(candidate)) {
            return Optional.of(candidate);
        }
        if (Files.isDirectory(candidate)) {
            for (String fileName : candidateNames(name)) {
                Path child = candidate.resolve(fileName);
                if (Files.isRegularFile(child)) {
                    return Optional.of(child);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> searchPath(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        List<String> names = candidateNames(name);
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String fileName : names) {
                try {
                    Path candidate = Path.of(dir, fileName);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate);
                    }
                } catch (RuntimeException ignored) {
                    // malformed PATH entry
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> candidateNames(String name) {
        List<String> names = new ArrayList<>();
        names.add(name);
        if (isWindows()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".exe") && !lower.endsWith(".cmd") && !lower.endsWith(".bat")) {
                names.add(name + ".exe");
                names.add(name + ".cmd");
            }
        }
        return names;
    }

    private static boolean looksLikePath(String value) {
        return value.contains("/") || value.contains("\\");
    }

    private static String fileNameWithoutExtension(String value) {
        String normalized = value.replace('\\', '/');
        String last = normalized.substring(normalized.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        return dot > 0 ? last.substring(0, dot) : last;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
