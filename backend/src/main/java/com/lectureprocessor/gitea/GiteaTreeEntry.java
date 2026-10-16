package com.lectureprocessor.gitea;

/**
 * One entry of a Git tree listing.
 *
 * @param path repository-relative path
 * @param type {@code blob} or {@code tree}
 * @param sha  Git object id (used to fetch the blob)
 * @param size object size in bytes ({@code -1} when unknown)
 */
public record GiteaTreeEntry(String path, String type, String sha, long size) {
}
