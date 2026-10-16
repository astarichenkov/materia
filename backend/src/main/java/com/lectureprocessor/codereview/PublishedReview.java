package com.lectureprocessor.codereview;

import java.util.List;

/**
 * Outcome of attempting to publish a review to Gitea.
 */
public record PublishedReview(
        boolean published,
        boolean superseded,
        boolean duplicate,
        String commentId,
        long publishMs,
        String note) {

    public static PublishedReview superseded(String note) {
        return new PublishedReview(false, true, false, null, 0, note);
    }

    public static PublishedReview duplicate(String commentId) {
        return new PublishedReview(false, false, true, commentId, 0, "AI review уже опубликован для этого commit");
    }
}
