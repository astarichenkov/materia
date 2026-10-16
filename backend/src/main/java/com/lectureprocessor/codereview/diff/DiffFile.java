package com.lectureprocessor.codereview.diff;

import java.util.List;
import java.util.Set;

/**
 * One file in a unified diff.
 *
 * @param path        new path ({@code /dev/null} handling removed files)
 * @param oldPath     previous path for renames (may be null)
 * @param changeType  ADDED / MODIFIED / DELETED / RENAMED / BINARY
 * @param hunks       parsed hunks
 * @param changedLines new-file line numbers that are added or context (candidate anchors)
 */
public record DiffFile(String path, String oldPath, ChangeType changeType, List<DiffHunk> hunks,
        Set<Integer> changedLines) {

    public enum ChangeType {
        ADDED, MODIFIED, DELETED, RENAMED, BINARY
    }

    public DiffFile {
        hunks = hunks == null ? List.of() : List.copyOf(hunks);
        changedLines = changedLines == null ? Set.of() : Set.copyOf(changedLines);
    }

    public boolean hasHunks() {
        return !hunks.isEmpty();
    }

    /** True when the given new-file line belongs to the diff (added or context). */
    public boolean anchorsLine(int line) {
        return changedLines.contains(line);
    }
}
