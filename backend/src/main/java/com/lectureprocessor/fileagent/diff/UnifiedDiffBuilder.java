package com.lectureprocessor.fileagent.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic line-based unified diff generator. The LLM never supplies a
 * patch: Java derives the diff from validated old/new file contents, so the
 * displayed diff always matches what will be written.
 */
public final class UnifiedDiffBuilder {

    private static final int CONTEXT = 3;

    private UnifiedDiffBuilder() {
    }

    public static String build(String path, String oldContent, String newContent) {
        List<String> oldLines = lines(oldContent);
        List<String> newLines = lines(newContent);
        List<Change> changes = diff(oldLines, newLines);
        if (changes.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("--- a/").append(path).append('\n');
        out.append("+++ b/").append(path).append('\n');
        for (Hunk hunk : hunks(changes, oldLines.size(), newLines.size())) {
            out.append("@@ -").append(hunk.oldStart()).append(',').append(hunk.oldCount())
                    .append(" +").append(hunk.newStart()).append(',').append(hunk.newCount())
                    .append(" @@\n");
            for (Line line : hunk.lines()) {
                out.append(line.prefix()).append(line.text()).append('\n');
            }
        }
        return out.toString();
    }

    public static boolean isEmpty(String diff) {
        return diff == null || diff.isBlank();
    }

    private static List<String> lines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }

    private static List<Change> diff(List<String> oldLines, List<String> newLines) {
        int n = oldLines.size();
        int m = newLines.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Change> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                result.add(new Change(' ', oldLines.get(i), i + 1, j + 1));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                result.add(new Change('-', oldLines.get(i), i + 1, j));
                i++;
            } else {
                result.add(new Change('+', newLines.get(j), i, j + 1));
                j++;
            }
        }
        while (i < n) {
            result.add(new Change('-', oldLines.get(i), i + 1, j));
            i++;
        }
        while (j < m) {
            result.add(new Change('+', newLines.get(j), i, j + 1));
            j++;
        }
        return result;
    }

    private static List<Hunk> hunks(List<Change> changes, int oldSize, int newSize) {
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i).prefix() != ' ') {
                changed.add(i);
            }
        }
        List<Hunk> hunks = new ArrayList<>();
        int index = 0;
        while (index < changed.size()) {
            int start = Math.max(0, changed.get(index) - CONTEXT);
            int end = Math.min(changes.size(), changed.get(index) + CONTEXT + 1);
            index++;
            while (index < changed.size() && changed.get(index) - CONTEXT <= end) {
                end = Math.min(changes.size(), changed.get(index) + CONTEXT + 1);
                index++;
            }
            List<Line> lines = new ArrayList<>();
            int oldStart = -1;
            int newStart = -1;
            int oldCount = 0;
            int newCount = 0;
            for (int k = start; k < end; k++) {
                Change change = changes.get(k);
                char prefix = change.prefix();
                lines.add(new Line(prefix, change.text()));
                if (prefix != '+') {
                    if (oldStart < 0) {
                        oldStart = change.oldLine();
                    }
                    oldCount++;
                }
                if (prefix != '-') {
                    if (newStart < 0) {
                        newStart = change.newLine();
                    }
                    newCount++;
                }
            }
            if (oldStart < 0) {
                oldStart = Math.max(1, oldSize + 1);
            }
            if (newStart < 0) {
                newStart = Math.max(1, newSize + 1);
            }
            hunks.add(new Hunk(oldStart, oldCount, newStart, newCount, lines));
        }
        return hunks;
    }

    private record Change(char prefix, String text, int oldLine, int newLine) {
    }

    private record Line(char prefix, String text) {
    }

    private record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<Line> lines) {
    }
}
