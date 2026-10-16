package com.lectureprocessor.codereview;

import java.util.List;

/** Raw, unvalidated finding extracted from the model JSON. */
public record RawFinding(
        String category,
        String severity,
        String file,
        Integer line,
        String title,
        String description,
        String suggestion,
        Double confidence) {
}
