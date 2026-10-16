package com.lectureprocessor.codereview.code;

/**
 * Outcome of ensuring the source-code index for one commit exists.
 */
public record CodeIndexResult(
        boolean indexed,
        boolean reused,
        boolean unavailable,
        int files,
        int chunks,
        String commitSha,
        String message) {

    public static CodeIndexResult reused(String commitSha, int files, int chunks) {
        return new CodeIndexResult(false, true, false, files, chunks, commitSha, null);
    }

    public static CodeIndexResult unavailable(String commitSha, String message) {
        return new CodeIndexResult(false, false, true, 0, 0, commitSha, message);
    }
}
