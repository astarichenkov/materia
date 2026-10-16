package com.lectureprocessor.codereview;

/**
 * One validated review finding.
 *
 * @param category    finding category
 * @param severity    finding severity
 * @param file        repository-relative path (must be a changed/analysed file)
 * @param line        1-based line in the new file (nullable)
 * @param title       short title
 * @param description explanation
 * @param suggestion  recommended change (nullable)
 * @param confidence  model confidence in [0,1] (nullable)
 */
public record ReviewFinding(
        ReviewCategory category,
        ReviewSeverity severity,
        String file,
        Integer line,
        String title,
        String description,
        String suggestion,
        Double confidence) {

    /** Stable dedup key: same problem reported twice is collapsed. */
    public String dedupKey() {
        return (file == null ? "" : file) + "|" + (line == null ? "" : line) + "|"
                + category + "|" + normalize(title);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-zа-я0-9]+", " ").strip();
    }
}
