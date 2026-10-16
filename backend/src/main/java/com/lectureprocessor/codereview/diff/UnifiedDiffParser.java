package com.lectureprocessor.codereview.diff;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a unified diff into per-file hunks and tracks new-file line anchors.
 * Pure and dependency-free so it can be unit-tested exhaustively and reused for
 * both the Gitea diff and local git output.
 */
public final class UnifiedDiffParser {

    private static final Pattern HUNK = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");
    private static final Pattern DIFF_GIT = Pattern.compile("^diff --git a/(.*) b/(.*)$");

    private UnifiedDiffParser() {
    }

    public static List<DiffFile> parse(String diff) {
        List<DiffFile> files = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return files;
        }
        String path = null;
        String oldPath = null;
        String nextPath = null;
        String nextOldPath = null;
        boolean binary = false;
        boolean newFile = false;
        boolean deleted = false;
        List<DiffHunk> hunks = new ArrayList<>();
        Set<Integer> changed = new LinkedHashSet<>();
        int oldLine = 0;
        int newLine = 0;
        List<DiffLine> hunkLines = new ArrayList<>();
        int hunkOldStart = 0;
        int hunkOldCount = 0;
        int hunkNewStart = 0;
        int hunkNewCount = 0;
        boolean inHunk = false;

        String[] lines = diff.split("\n", -1);
        for (String line : lines) {
            Matcher git = DIFF_GIT.matcher(line);
            if (git.matches()) {
                flush(files, path, oldPath, binary, newFile, deleted, hunks, changed);
                path = null;
                oldPath = null;
                binary = false;
                newFile = false;
                deleted = false;
                hunks = new ArrayList<>();
                changed = new LinkedHashSet<>();
                inHunk = false;
                nextOldPath = unquoteA(git.group(1));
                nextPath = unquoteB(git.group(2));
                path = nextPath;
                oldPath = nextOldPath;
                continue;
            }
            if (line.startsWith("new file mode")) {
                newFile = true;
                continue;
            }
            if (line.startsWith("deleted file mode")) {
                deleted = true;
                continue;
            }
            if (line.startsWith("rename from ")) {
                oldPath = line.substring("rename from ".length()).strip();
                continue;
            }
            if (line.startsWith("rename to ")) {
                path = line.substring("rename to ".length()).strip();
                continue;
            }
            if (line.startsWith("--- ")) {
                String candidate = line.substring(4).strip();
                if (!"/dev/null".equals(candidate)) {
                    oldPath = stripPrefix(candidate);
                }
                continue;
            }
            if (line.startsWith("+++ ")) {
                String candidate = line.substring(4).strip();
                if (!"/dev/null".equals(candidate)) {
                    path = stripPrefix(candidate);
                }
                continue;
            }
            if (line.startsWith("Binary files") || line.startsWith("GIT binary patch")) {
                binary = true;
                continue;
            }
            Matcher hunk = HUNK.matcher(line);
            if (hunk.matches()) {
                if (inHunk) {
                    hunks.add(new DiffHunk(hunkOldStart, hunkOldCount, hunkNewStart, hunkNewCount, hunkLines));
                }
                inHunk = true;
                hunkOldStart = Integer.parseInt(hunk.group(1));
                hunkOldCount = hunk.group(2) == null ? 1 : Integer.parseInt(hunk.group(2));
                hunkNewStart = Integer.parseInt(hunk.group(3));
                hunkNewCount = hunk.group(4) == null ? 1 : Integer.parseInt(hunk.group(4));
                oldLine = hunkOldStart;
                newLine = hunkNewStart;
                hunkLines = new ArrayList<>();
                continue;
            }
            if (!inHunk) {
                continue;
            }
            if (line.startsWith("\\")) {
                continue;
            }
            if (line.startsWith("+")) {
                hunkLines.add(new DiffLine(DiffLine.Type.ADDED, 0, newLine, line.substring(1)));
                changed.add(newLine);
                newLine++;
            } else if (line.startsWith("-")) {
                hunkLines.add(new DiffLine(DiffLine.Type.REMOVED, oldLine, 0, line.substring(1)));
                oldLine++;
            } else {
                hunkLines.add(new DiffLine(DiffLine.Type.CONTEXT, oldLine, newLine,
                        line.startsWith(" ") ? line.substring(1) : line));
                changed.add(newLine);
                oldLine++;
                newLine++;
            }
        }
        if (inHunk) {
            hunks.add(new DiffHunk(hunkOldStart, hunkOldCount, hunkNewStart, hunkNewCount, hunkLines));
        }
        flush(files, path, oldPath, binary, newFile, deleted, hunks, changed);
        return files;
    }

    private static void flush(List<DiffFile> files, String path, String oldPath, boolean binary,
            boolean newFile, boolean deleted, List<DiffHunk> hunks, Set<Integer> changed) {
        if (path == null && oldPath == null) {
            return;
        }
        String effectivePath = path != null ? path : oldPath;
        DiffFile.ChangeType type;
        if (binary) {
            type = DiffFile.ChangeType.BINARY;
        } else if (deleted) {
            type = DiffFile.ChangeType.DELETED;
        } else if (newFile) {
            type = DiffFile.ChangeType.ADDED;
        } else if (oldPath != null && !oldPath.equals(effectivePath)) {
            type = DiffFile.ChangeType.RENAMED;
        } else {
            type = DiffFile.ChangeType.MODIFIED;
        }
        files.add(new DiffFile(effectivePath, oldPath, type, new ArrayList<>(hunks),
                new LinkedHashSet<>(changed)));
    }

    private static String stripPrefix(String value) {
        String stripped = value;
        if (stripped.startsWith("a/") || stripped.startsWith("b/")) {
            stripped = stripped.substring(2);
        }
        return stripped;
    }

    private static String unquoteA(String value) {
        String stripped = value.strip();
        return stripPrefix(stripped);
    }

    private static String unquoteB(String value) {
        String stripped = value.strip();
        return stripPrefix(stripped);
    }
}
