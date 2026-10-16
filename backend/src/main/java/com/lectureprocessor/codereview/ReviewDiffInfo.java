package com.lectureprocessor.codereview;

import java.util.List;
import java.util.Set;

import com.lectureprocessor.codereview.diff.DiffFile;

/**
 * Parsed diff plus retrieval hints, produced once per review.
 *
 * @param files        parsed diff files (with line anchors)
 * @param identifiers  identifiers extracted from added lines for retrieval
 * @param truncated    true when the diff was cut to the configured budget
 */
public record ReviewDiffInfo(List<DiffFile> files, Set<String> identifiers, boolean truncated) {

    public ReviewDiffInfo {
        files = files == null ? List.of() : List.copyOf(files);
        identifiers = identifiers == null ? Set.of() : Set.copyOf(identifiers);
    }

    public static ReviewDiffInfo empty() {
        return new ReviewDiffInfo(List.of(), Set.of(), false);
    }
}
