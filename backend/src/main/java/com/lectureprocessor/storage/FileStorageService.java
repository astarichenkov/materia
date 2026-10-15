package com.lectureprocessor.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;

/**
 * Owns the on-disk layout for uploaded media and generated artifacts:
 *
 * <pre>
 *   &lt;root&gt;/lectures/&lt;lectureId&gt;/source.&lt;ext&gt;
 *   &lt;root&gt;/lectures/&lt;lectureId&gt;/audio.wav
 *   &lt;root&gt;/lectures/&lt;lectureId&gt;/transcript.json
 *   &lt;root&gt;/lectures/&lt;lectureId&gt;/transcript.md
 *   &lt;root&gt;/lectures/&lt;lectureId&gt;/notes.md / notes.docx
 *   &lt;root&gt;/lectures/&lt;lectureId&gt;/slides/*.jpg
 * </pre>
 */
@Service
public class FileStorageService {

    private final Path root;

    public FileStorageService(AppProperties properties) {
        this.root = Path.of(properties.storage().root()).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path lectureDir(String lectureId) {
        Path dir = root.resolve("lectures").resolve(sanitize(lectureId));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new StorageException("Не удалось создать каталог лекции: " + dir, e);
        }
        return dir;
    }

    public Path slidesDir(String lectureId) {
        Path dir = lectureDir(lectureId).resolve("slides");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new StorageException("Не удалось создать каталог слайдов: " + dir, e);
        }
        return dir;
    }

    public Path saveSource(String lectureId, String originalFilename, InputStream content) {
        String extension = extensionOf(originalFilename);
        Path target = lectureDir(lectureId).resolve("source" + extension);
        try {
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("Не удалось сохранить загруженный файл", e);
        }
        return target;
    }

    public Path resolve(String lectureId, String relative) {
        Path base = lectureDir(lectureId).toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            throw new StorageException("Недопустимый путь артефакта: " + relative);
        }
        return resolved;
    }

    /** Relative path of an artifact inside the lecture directory (for manifests). */
    public String relative(String lectureId, Path absolute) {
        Path base = lectureDir(lectureId).toAbsolutePath().normalize();
        Path normalized = absolute.toAbsolutePath().normalize();
        if (!normalized.startsWith(base)) {
            throw new StorageException("Путь вне workspace лекции: " + normalized);
        }
        return base.relativize(normalized).toString().replace('\\', '/');
    }

    public void deleteLectureData(String lectureId) {
        Path dir = root.resolve("lectures").resolve(sanitize(lectureId));
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best effort cleanup
                        }
                    });
        } catch (IOException e) {
            throw new StorageException("Не удалось удалить данные лекции: " + lectureId, e);
        }
    }

    public static String extensionOf(String filename) {
        if (filename == null) {
            return ".bin";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return ".bin";
        }
        String ext = filename.substring(dot).toLowerCase();
        return ext.matches("\\.[a-z0-9]{1,5}") ? ext : ".bin";
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
