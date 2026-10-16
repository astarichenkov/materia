package com.lectureprocessor.codereview;

import java.util.Locale;

/** Review finding category, restricted to the categories the prompt requests. */
public enum ReviewCategory {
    BUG,
    ARCHITECTURE,
    SECURITY,
    RELIABILITY,
    TEST_COVERAGE,
    OTHER;

    public static ReviewCategory from(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (ReviewCategory category : values()) {
            if (category.name().equals(normalized)) {
                return category;
            }
        }
        return switch (normalized) {
            case "BUGS", "LOGIC", "CORRECTNESS" -> BUG;
            case "ARCH", "DESIGN" -> ARCHITECTURE;
            case "SEC" -> SECURITY;
            case "RELIABILITY_ISSUE", "RESILIENCE" -> RELIABILITY;
            case "TEST", "TESTS", "TESTING", "COVERAGE" -> TEST_COVERAGE;
            default -> OTHER;
        };
    }
}
