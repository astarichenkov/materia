package com.lectureprocessor.fileagent.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.lectureprocessor.codereview.code.PathGlob;
import com.lectureprocessor.fileagent.FileAgentException;
import com.lectureprocessor.fileagent.FileAgentProperties;

/**
 * Deterministic path and content guard for both file workspaces.
 *
 * <p>String prefix checks are not enough on Windows: junctions, symlinks and
 * short/UNC paths can escape a root. Every read and every write therefore
 * checks each component, rejects links, and compares the canonical path of the
 * nearest existing ancestor with the canonical workspace root.</p>
 */
@Service
public class WorkspacePathGuard {

    private static final int BINARY_SNIFF_BYTES = 8192;

    private final FileAgentProperties properties;

    public WorkspacePathGuard(FileAgentProperties properties) {
        this.properties = properties;
    }

    /** Validates a readable regular text file inside the workspace. */
    public ResolvedFile resolveRead(FileWorkspaceRef workspace, String relative) {
        ResolvedFile file = validate(workspace, relative, false);
        if (!Files.isRegularFile(file.absolutePath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAgentException("Файл не найден: " + file.relativePath());
        }
        requireTextAndSize(file);
        return file;
    }

    /** Validates a writable target path; the file itself may not exist yet. */
    public ResolvedFile resolveWrite(FileWorkspaceRef workspace, String relative) {
        ResolvedFile file = validate(workspace, relative, true);
        if (Files.exists(file.absolutePath(), LinkOption.NOFOLLOW_LINKS)) {
            requireTextAndSize(file);
        } else {
            ensureCreatable(file);
        }
        return file;
    }

    /** Rejects a path without touching the filesystem (used by proposal parsing). */
    public ResolvedFile validatePath(FileWorkspaceRef workspace, String relative) {
        return validate(workspace, relative, true);
    }

    public boolean isProtected(String relativePath) {
        String normalized = normalize(relativePath);
        return PathGlob.matchesAny(normalized, properties.protectedPatterns());
    }

    public boolean isWriteForbidden(String relativePath) {
        String normalized = normalize(relativePath);
        return isProtected(normalized)
                || PathGlob.matchesAny(normalized, properties.writeForbiddenPatterns());
    }

    private ResolvedFile validate(FileWorkspaceRef workspace, String relative, boolean write) {
        if (workspace == null) {
            throw new FileAgentException("Workspace не разрешён");
        }
        String normalized = normalize(relative);
        if (normalized.isEmpty()) {
            throw new FileAgentException("Пустой путь файла");
        }
        List<String> segments = segments(normalized);
        if (segments.size() > Math.max(1, properties.maxDepth())) {
            throw new FileAgentException("Слишком глубокая вложенность пути: " + normalized);
        }
        if (isProtected(normalized)) {
            throw new FileAgentException("Файл защищён политикой безопасности: " + normalized);
        }
        if (write && isWriteForbidden(normalized)) {
            throw new FileAgentException("Файл доступен только для чтения: " + normalized);
        }
        requireExtension(normalized);
        Path root = workspace.root();
        Path candidate = root.resolve(normalized).normalize();
        if (!candidate.startsWith(root)) {
            throw new FileAgentException("Путь вне workspace: " + relative);
        }
        assertNoLinks(workspace, candidate, write);
        return new ResolvedFile(workspace, normalized, candidate);
    }

    private void assertNoLinks(FileWorkspaceRef workspace, Path candidate, boolean write) {
        Path root = workspace.root();
        Path current = root;
        for (Path part : root.relativize(candidate)) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
            try {
                BasicFileAttributes attrs = Files.readAttributes(current,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    throw new FileAgentException("Символические ссылки и reparse points запрещены: "
                            + workspace.root().relativize(current));
                }
            } catch (IOException e) {
                throw new FileAgentException("Не удалось проверить путь: " + e.getMessage(), e);
            }
        }
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new FileAgentException("Не удалось определить существующий родительский путь");
        }
        try {
            Path real = existing.toRealPath();
            if (!real.startsWith(workspace.realRoot())) {
                throw new FileAgentException("Путь выходит за пределы workspace (symlink/junction)");
            }
            if (!write && !candidate.toRealPath().startsWith(workspace.realRoot())) {
                throw new FileAgentException("Путь выходит за пределы workspace");
            }
        } catch (IOException e) {
            throw new FileAgentException("Не удалось канонизировать путь: " + e.getMessage(), e);
        }
    }

    private void ensureCreatable(ResolvedFile file) {
        Path parent = file.absolutePath().getParent();
        Path root = file.workspace().root();
        if (parent == null || !parent.normalize().startsWith(root)) {
            throw new FileAgentException("Недопустимый родительский каталог");
        }
        if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAgentException("Родительский путь не является каталогом");
            }
        }
    }

    private void requireExtension(String normalized) {
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            throw new FileAgentException("Файлы без расширения запрещены: " + normalized);
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!properties.extensions().contains(extension)) {
            throw new FileAgentException("Расширение ." + extension + " не разрешено файловому агенту");
        }
    }

    private void requireTextAndSize(ResolvedFile file) {
        try {
            long size = Files.size(file.absolutePath());
            if (size > Math.max(1, properties.maxFileBytes())) {
                throw new FileAgentException("Файл слишком большой: " + file.relativePath()
                        + " (" + size + " > " + properties.maxFileBytes() + " байт)");
            }
            byte[] head = readHead(file.absolutePath());
            for (byte b : head) {
                if (b == 0) {
                    throw new FileAgentException("Бинарные файлы запрещены: " + file.relativePath());
                }
            }
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file.absolutePath())));
        } catch (CharacterCodingException e) {
            throw new FileAgentException("Файл не является валидным UTF-8: " + file.relativePath());
        } catch (IOException e) {
            throw new FileAgentException("Не удалось прочитать файл: " + file.relativePath(), e);
        }
    }

    private static byte[] readHead(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(BINARY_SNIFF_BYTES);
        }
    }

    /** Canonical relative path: POSIX separators, no absolute/UNC/drive/`.`/`..`. */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new FileAgentException("Путь не задан");
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            throw new FileAgentException("Пустой путь файла");
        }
        if (value.indexOf('\0') >= 0) {
            throw new FileAgentException("Недопустимый символ в пути");
        }
        String unified = value.replace('\\', '/');
        if (unified.startsWith("/") || unified.startsWith("//")) {
            throw new FileAgentException("Абсолютные и UNC пути запрещены: " + raw);
        }
        if (unified.matches("^[A-Za-z]:.*")) {
            throw new FileAgentException("Абсолютные пути с буквой диска запрещены: " + raw);
        }
        List<String> parts = new ArrayList<>();
        for (String part : unified.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                throw new FileAgentException("Path traversal запрещён: " + raw);
            }
            if (part.equalsIgnoreCase(".git") || part.equalsIgnoreCase(".secrets")) {
                throw new FileAgentException("Служебный каталог запрещён: " + raw);
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            throw new FileAgentException("Путь не указывает на файл");
        }
        return String.join("/", parts);
    }

    private static List<String> segments(String normalized) {
        return List.of(normalized.split("/"));
    }
}
