package com.lectureprocessor.gitea;

/**
 * One changed file in a pull request, as reported by the Gitea files API.
 *
 * @param filename         current path
 * @param previousFilename previous path for renames (may be null)
 * @param status           added / modified / removed / renamed / copied
 * @param additions        added line count
 * @param deletions        removed line count
 * @param changes          total changed line count
 */
public record GiteaChangedFile(
        String filename,
        String previousFilename,
        String status,
        int additions,
        int deletions,
        int changes) {
}
