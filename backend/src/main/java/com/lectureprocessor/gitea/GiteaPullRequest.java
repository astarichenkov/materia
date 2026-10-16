package com.lectureprocessor.gitea;

/**
 * Minimal, read-only view of a Gitea pull request.
 */
public record GiteaPullRequest(
        long number,
        String title,
        String state,
        boolean merged,
        String body,
        String baseRef,
        String baseSha,
        String headRef,
        String headSha,
        String htmlUrl,
        String author) {
}
