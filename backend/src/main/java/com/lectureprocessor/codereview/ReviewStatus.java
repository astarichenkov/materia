package com.lectureprocessor.codereview;

/** Lifecycle of a code-review job. Stored as text for readability/queries. */
public final class ReviewStatus {

    public static final String QUEUED = "QUEUED";
    public static final String FETCHING_DIFF = "FETCHING_DIFF";
    public static final String RETRIEVING_CONTEXT = "RETRIEVING_CONTEXT";
    public static final String ANALYZING = "ANALYZING";
    public static final String VALIDATING = "VALIDATING";
    public static final String PUBLISHING = "PUBLISHING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String SUPERSEDED = "SUPERSEDED";

    private ReviewStatus() {
    }

    public static boolean terminal(String status) {
        return COMPLETED.equals(status) || FAILED.equals(status) || CANCELLED.equals(status)
                || SUPERSEDED.equals(status);
    }
}
