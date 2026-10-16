package com.lectureprocessor.gitea;

/**
 * A Gitea issue/PR comment. Only the fields needed for duplicate detection and
 * safe publishing are exposed.
 */
public record GiteaComment(long id, String body, String author, String createdAt) {
}
