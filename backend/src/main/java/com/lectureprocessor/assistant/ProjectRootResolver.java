package com.lectureprocessor.assistant;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;

/**
 * Resolves the project root used by the developer assistant and the MCP server.
 * Explicit configuration wins; otherwise the working directory is walked up
 * until a directory containing {@code README.md} (and {@code docs/} or
 * {@code pom.xml}) is found. Only paths inside the resolved root may be read.
 */
@Component
public class ProjectRootResolver {

    private static final int MAX_DEPTH = 6;

    private final AppProperties properties;
    private volatile Path cached;

    public ProjectRootResolver(AppProperties properties) {
        this.properties = properties;
    }

    public Path resolve() {
        Path result = cached;
        if (result != null) {
            return result;
        }
        result = detect();
        cached = result;
        return result;
    }

    /** Resolves a project-relative path, rejecting escapes outside the root. */
    public Path resolveInside(String relative) {
        Path root = resolve();
        Path candidate = root.resolve(relative == null ? "" : relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Путь вне проекта запрещён: " + relative);
        }
        return candidate;
    }

    private Path detect() {
        AppProperties.Assistant assistant = properties.assistant();
        String configured = assistant == null ? null : assistant.projectRoot();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < MAX_DEPTH && dir != null; depth++) {
            if (looksLikeProject(dir)) {
                return dir;
            }
            dir = dir.getParent();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static boolean looksLikeProject(Path dir) {
        boolean readme = Files.isRegularFile(dir.resolve("README.md"));
        boolean docs = Files.isDirectory(dir.resolve("docs"));
        boolean pom = Files.isRegularFile(dir.resolve("pom.xml"));
        return readme && (docs || pom);
    }
}
