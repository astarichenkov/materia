package com.lectureprocessor.assistant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;

/**
 * Loads the project documentation corpus ({@code README.md} and
 * {@code docs/**}) from the resolved project root. Only files inside the root
 * are read, hidden/build directories are skipped and {@code AGENTS.md} is
 * excluded because it contains internal agent instructions rather than product
 * documentation.
 */
@Service
public class DocumentationCorpusService {

    private static final Logger log = LoggerFactory.getLogger(DocumentationCorpusService.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".md", ".txt", ".sql");
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", "target", "node_modules", "snapshots", ".idea", ".mvn");
    private static final Set<String> EXCLUDED_FILES = Set.of("agents.md");
    private static final int MAX_WALK_DEPTH = 5;

    private final AppProperties properties;
    private final ProjectRootResolver roots;

    public DocumentationCorpusService(AppProperties properties, ProjectRootResolver roots) {
        this.properties = properties;
        this.roots = roots;
    }

    public List<DocSource> load() {
        Path root = roots.resolve();
        List<DocSource> sources = new ArrayList<>();
        List<String> configured = properties.assistant().docsPaths();
        if (configured == null || configured.isEmpty()) {
            return sources;
        }
        for (String entry : configured) {
            Path path = roots.resolveInside(entry);
            if (Files.isRegularFile(path)) {
                read(path, root).ifPresent(sources::add);
            } else if (Files.isDirectory(path)) {
                walk(path, root, sources);
            } else {
                log.debug("Documentation path not found, skipped: {}", entry);
            }
        }
        sources.sort(Comparator.comparing(DocSource::path));
        return sources;
    }

    private void walk(Path directory, Path root, List<DocSource> sources) {
        try (Stream<Path> stream = Files.walk(directory, MAX_WALK_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::supported)
                    .forEach(file -> read(file, root).ifPresent(sources::add));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать документацию: " + directory, e);
        }
    }

    private boolean supported(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (EXCLUDED_FILES.contains(name)) {
            return false;
        }
        for (Path segment : file) {
            if (SKIPPED_DIRECTORIES.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private java.util.Optional<DocSource> read(Path file, Path root) {
        try {
            long size = Files.size(file);
            if (size <= 0 || size > properties.assistant().maxFileBytes()) {
                log.debug("Documentation file skipped by size: {} ({} bytes)", file, size);
                return java.util.Optional.empty();
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String relative = root.relativize(file).toString().replace('\\', '/');
            return java.util.Optional.of(new DocSource(relative, titleOf(content, file), content));
        } catch (IOException e) {
            log.warn("Cannot read documentation file {}: {}", file, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private static String titleOf(String content, Path file) {
        for (String line : content.split("\n", 40)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).strip();
            }
        }
        return file.getFileName().toString();
    }
}
