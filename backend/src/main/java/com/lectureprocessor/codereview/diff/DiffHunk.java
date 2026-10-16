package com.lectureprocessor.codereview.diff;

import java.util.List;

/** A single unified-diff hunk. */
public record DiffHunk(int oldStart, int oldCount, int newStart, int newCount, List<DiffLine> lines) {

    public DiffHunk {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
