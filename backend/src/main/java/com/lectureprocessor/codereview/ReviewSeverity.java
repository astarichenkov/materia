package com.lectureprocessor.codereview;

import java.util.Locale;

/** Review finding severity with a stable ordering for the report. */
public enum ReviewSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public static ReviewSeverity from(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        for (ReviewSeverity severity : values()) {
            if (severity.name().equals(normalized)) {
                return severity;
            }
        }
        return switch (normalized) {
            case "BLOCKER", "SEVERE" -> CRITICAL;
            case "MAJOR", "ERROR" -> HIGH;
            case "MINOR", "WARNING", "WARN" -> MEDIUM;
            case "INFO", "TRIVIAL" -> LOW;
            default -> MEDIUM;
        };
    }

    public int weight() {
        return ordinal();
    }
}
