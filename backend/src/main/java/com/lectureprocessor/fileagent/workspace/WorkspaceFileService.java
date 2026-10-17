package com.lectureprocessor.fileagent.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.lectureprocessor.codereview.code.PathGlob;
import com.lectureprocessor.fileagent.FileAgentException;
import com.lectureprocessor.fileagent.FileAgentProperties;

/**
 * Bounded read-only file operations used by both the MCP file tools and the
 * orchestrator. Every path goes through {@link WorkspacePathGuard}.
 */
@Service
public class WorkspaceFileService {

    private static final Set<String> SKIPPED_DIRS = Set.of(".git", ".secrets", "target", "build",
            "node_modules", "data", "snapshots", ".idea", ".mvn", ".settings");
    private static final int MAX_SCANNED_FILES = 4000;
    private static final int SNIPPET_CHARS = 220;

    private final WorkspacePathGuard guard;
    private final FileAgentProperties properties;

    public WorkspaceFileService(WorkspacePathGuard guard, FileAgentProperties properties) {
        this.guard = guard;
        this.properties = properties;
    }

    public FileContent read(FileWorkspaceRef workspace, String rawPath, Integer startLine,
            Integer maxLines) {
        ResolvedFile file = guard.resolveRead(workspace, rawPath);
        try {
            byte[] bytes = Files.readAllBytes(file.absolutePath());
            List<String> all = new ArrayList<>(List.of(new String(bytes, StandardCharsets.UTF_8)
                    .split("\n", -1)));
            if (!all.isEmpty() && all.getLast().isEmpty()) {
                all.removeLast();
            }
            int total = all.size();
            int from = startLine == null ? 1 : Math.max(1, startLine);
            int cap = maxLines == null ? Math.max(1, properties.maxReadLines())
                    : Math.max(1, Math.min(maxLines, properties.maxReadLines()));
            int to = Math.min(total, from - 1 + cap);
            List<String> slice = from - 1 >= total ? List.of() : all.subList(from - 1, to);
            boolean truncated = from > 1 || to < total;
            return new FileContent(workspace.id(), file.relativePath(), List.copyOf(slice), from, total,
                    truncated, sha256(bytes), bytes.length,
                    Files.getLastModifiedTime(file.absolutePath()).toInstant());
        } catch (IOException e) {
            throw new FileAgentException("Не удалось прочитать файл: " + file.relativePath(), e);
        }
    }

    /** Full file text (bounded by max-file-bytes) for change validation. */
    public FileContent readFull(FileWorkspaceRef workspace, String rawPath) {
        ResolvedFile file = guard.resolveRead(workspace, rawPath);
        try {
            byte[] bytes = Files.readAllBytes(file.absolutePath());
            List<String> all = new ArrayList<>(List.of(new String(bytes, StandardCharsets.UTF_8)
                    .split("\n", -1)));
            if (!all.isEmpty() && all.getLast().isEmpty()) {
                all.removeLast();
            }
            return new FileContent(workspace.id(), file.relativePath(), List.copyOf(all), 1,
                    all.size(), false, sha256(bytes), bytes.length,
                    Files.getLastModifiedTime(file.absolutePath()).toInstant());
        } catch (IOException e) {
            throw new FileAgentException("Не удалось прочитать файл: " + file.relativePath(), e);
        }
    }

    public FileMetadata metadata(FileWorkspaceRef workspace, String rawPath) {
        ResolvedFile file = guard.resolveRead(workspace, rawPath);
        try {
            byte[] bytes = Files.readAllBytes(file.absolutePath());
            return new FileMetadata(workspace.id(), file.relativePath(), bytes.length,
                    contentType(file.relativePath()), Files.getLastModifiedTime(file.absolutePath()).toInstant(),
                    sha256(bytes), guard.isProtected(file.relativePath()),
                    guard.isWriteForbidden(file.relativePath()));
        } catch (IOException e) {
            throw new FileAgentException("Не удалось получить метаданные: " + file.relativePath(), e);
        }
    }

    public String hash(FileWorkspaceRef workspace, String rawPath) {
        ResolvedFile file = guard.resolveRead(workspace, rawPath);
        try {
            return sha256(Files.readAllBytes(file.absolutePath()));
        } catch (IOException e) {
            throw new FileAgentException("Не удалось вычислить hash: " + file.relativePath(), e);
        }
    }

    public List<FileSearchHit> searchFiles(FileWorkspaceRef workspace, String query, String mask,
            int limit) {
        String q = query == null ? "" : query.strip();
        Set<String> tokens = tokens(q);
        int cap = Math.max(1, Math.min(limit, properties.maxSearchResults()));
        List<FileSearchHit> hits = new ArrayList<>();
        walk(workspace, (relative, absolute, size) -> {
            if (mask != null && !mask.isBlank() && !matchesMask(relative, mask.strip())) {
                return;
            }
            double score = nameScore(relative, tokens);
            String match = score > 0 ? "имя/путь" : null;
            if (score <= 0 && !tokens.isEmpty() && size <= Math.min(properties.maxFileBytes(), 200_000)) {
                String text = readText(absolute);
                if (text != null) {
                    String lower = text.toLowerCase(Locale.ROOT);
                    int found = 0;
                    String snippet = null;
                    for (String token : tokens) {
                        int index = lower.indexOf(token);
                        if (index >= 0) {
                            found++;
                            if (snippet == null) {
                                snippet = snippet(text, index);
                            }
                        }
                    }
                    if (found > 0) {
                        score = Math.min(1.0, found / (double) tokens.size());
                        match = snippet;
                    }
                }
            }
            if (score > 0) {
                hits.add(new FileSearchHit(relative, Math.round(score * 1000) / 1000.0, match, size));
            }
        });
        return hits.stream()
                .sorted(Comparator.comparingDouble(FileSearchHit::score).reversed()
                        .thenComparing(FileSearchHit::path))
                .limit(cap)
                .toList();
    }

    public List<SymbolHit> searchSymbols(FileWorkspaceRef workspace, String symbol, int limit) {
        String needle = symbol == null ? "" : symbol.strip();
        if (needle.isEmpty()) {
            return List.of();
        }
        int cap = Math.max(1, Math.min(limit, properties.maxSearchResults()));
        Pattern declaration = Pattern.compile("\\b(class|interface|enum|record)\\s+" + Pattern.quote(needle)
                + "\\b");
        Pattern method = Pattern.compile("\\b" + Pattern.quote(needle) + "\\s*\\(");
        List<SymbolHit> hits = new ArrayList<>();
        walk(workspace, (relative, absolute, size) -> {
            if (hits.size() >= cap * 3 || !isSource(relative)) {
                return;
            }
            if (size > Math.min(properties.maxFileBytes(), 400_000)) {
                return;
            }
            String text = readText(absolute);
            if (text == null) {
                return;
            }
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length && hits.size() < cap * 3; i++) {
                String line = lines[i];
                Matcher decl = declaration.matcher(line);
                Matcher meth = method.matcher(line);
                if (decl.find()) {
                    hits.add(new SymbolHit(relative, needle, decl.group(1).toUpperCase(Locale.ROOT),
                            i + 1, Math.min(lines.length, i + 20), line.strip()));
                } else if (meth.find() && !line.strip().startsWith("//")) {
                    hits.add(new SymbolHit(relative, needle, "METHOD", i + 1,
                            Math.min(lines.length, i + 12), line.strip()));
                } else if (line.contains(needle)) {
                    hits.add(new SymbolHit(relative, needle, "REFERENCE", i + 1, i + 1, line.strip()));
                }
            }
        });
        return hits.stream().limit(cap).toList();
    }

    public List<String> list(FileWorkspaceRef workspace, String subdir, int limit) {
        int cap = Math.max(1, Math.min(limit, properties.maxSearchResults() * 10));
        String prefix = subdir == null || subdir.isBlank() ? "" : WorkspacePathGuard.normalize(subdir) + "/";
        List<String> files = new ArrayList<>();
        walk(workspace, (relative, absolute, size) -> {
            if (relative.startsWith(prefix)) {
                files.add(relative);
            }
        });
        return files.stream().sorted().limit(cap).toList();
    }

    private void walk(FileWorkspaceRef workspace, FileVisitor visitor) {
        Path root = workspace.root();
        int maxDepth = Math.max(1, properties.maxDepth());
        final int[] scanned = {0};
        try {
            Files.walkFileTree(root, java.util.Set.of(), maxDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (SKIPPED_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String relative = relative(root, dir);
                    if (guard.isProtected(relative + "/placeholder.txt")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (scanned[0]++ >= MAX_SCANNED_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = relative(root, file);
                    if (guard.isProtected(relative) || !extensionAllowed(relative)) {
                        return FileVisitResult.CONTINUE;
                    }
                    visitor.visit(relative, file, attrs.size());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new FileAgentException("Не удалось просканировать workspace: " + e.getMessage(), e);
        }
    }

    private boolean extensionAllowed(String relative) {
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return properties.extensions().contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean isSource(String relative) {
        String lower = relative.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".js")
                || lower.endsWith(".ts");
    }

    private static boolean matchesMask(String relative, String mask) {
        if (!mask.contains("*") && !mask.contains("?")) {
            return relative.toLowerCase(Locale.ROOT).endsWith(mask.toLowerCase(Locale.ROOT));
        }
        return PathGlob.matches(relative, mask) || PathGlob.matches(relative, "**/" + mask);
    }

    private static double nameScore(String relative, Set<String> tokens) {
        if (tokens.isEmpty()) {
            return 0;
        }
        String lower = relative.toLowerCase(Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        double score = 0;
        for (String token : tokens) {
            if (name.contains(token)) {
                score = Math.max(score, 1.0);
            } else if (lower.contains(token)) {
                score = Math.max(score, 0.7);
            }
        }
        return score;
    }

    private static Set<String> tokens(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : query.split("[^A-Za-z0-9_А-Яа-яЁё]+")) {
            if (raw.length() >= 2) {
                tokens.add(raw.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private static String readText(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            for (byte b : bytes) {
                if (b == 0) {
                    return null;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String snippet(String text, int index) {
        int start = Math.max(0, index - 60);
        int end = Math.min(text.length(), index + SNIPPET_CHARS);
        return text.substring(start, end).replaceAll("\\s+", " ").strip();
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static String contentType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        if (lower.endsWith(".java")) {
            return "text/x-java";
        }
        if (lower.endsWith(".sql")) {
            return "application/sql";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "application/yaml";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".html")) {
            return "text/html";
        }
        return "text/plain";
    }

    /** Visitor used by bounded tree walks. */
    @FunctionalInterface
    private interface FileVisitor {
        void visit(String relative, Path absolute, long size);
    }
}
