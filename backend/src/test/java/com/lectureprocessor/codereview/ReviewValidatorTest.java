package com.lectureprocessor.codereview;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Validation, hallucination control and deduplication. */
class ReviewValidatorTest {

    private final ReviewValidator validator = new ReviewValidator();

    private static RawFinding finding(String file, Integer line, String title, String severity,
            Double confidence) {
        return new RawFinding("BUG", severity, file, line, title, "description text", "fix it", confidence);
    }

    @Test
    void dropsFindingsForFilesNotInTheDiff() {
        RawReview raw = new RawReview("summary", "HIGH",
                List.of(finding("src/Real.java", 3, "real", "HIGH", 0.9),
                        finding("src/Hallucinated.java", 1, "fake", "HIGH", 0.9)),
                List.of());
        ParsedReview parsed = validator.validate(raw, Set.of("src/Real.java"),
                Map.of("src/Real.java", Set.of(3)), 10);

        assertThat(parsed.findings()).hasSize(1);
        assertThat(parsed.findings().getFirst().file()).isEqualTo("src/Real.java");
    }

    @Test
    void nullsLineNumbersNotAnchoredInTheDiff() {
        RawReview raw = new RawReview("s", null,
                List.of(finding("src/A.java", 999, "off line", "MEDIUM", 0.8)), List.of());
        ParsedReview parsed = validator.validate(raw, Set.of("src/A.java"),
                Map.of("src/A.java", Set.of(10, 11)), 10);

        assertThat(parsed.findings()).hasSize(1);
        assertThat(parsed.findings().getFirst().line()).isNull();
    }

    @Test
    void deduplicatesIdenticalFindings() {
        RawReview raw = new RawReview("s", null,
                List.of(finding("src/A.java", 3, "Same issue", "HIGH", 0.9),
                        finding("src/A.java", 3, "same  issue", "HIGH", 0.8)),
                List.of());
        ParsedReview parsed = validator.validate(raw, Set.of("src/A.java"),
                Map.of("src/A.java", Set.of(3)), 10);
        assertThat(parsed.findings()).hasSize(1);
    }

    @Test
    void dropsLowConfidenceAndEmptyFindings() {
        RawReview raw = new RawReview("s", null,
                List.of(finding("src/A.java", 3, "uncertain", "LOW", 0.1),
                        new RawFinding("BUG", "HIGH", "src/A.java", 3, "  ", "text", null, 0.9)),
                List.of());
        ParsedReview parsed = validator.validate(raw, Set.of("src/A.java"), Map.of(), 10);
        assertThat(parsed.findings()).isEmpty();
    }

    @Test
    void capsNumberOfFindingsAndKeepsHighestSeverity() {
        List<RawFinding> findings = List.of(
                finding("src/A.java", 1, "low", "LOW", 0.9),
                finding("src/A.java", 2, "high", "HIGH", 0.9),
                finding("src/A.java", 3, "critical", "CRITICAL", 0.9));
        ParsedReview parsed = validator.validate(new RawReview("s", null, findings, List.of()),
                Set.of("src/A.java"), Map.of(), 2);
        assertThat(parsed.findings()).hasSize(2);
        assertThat(parsed.findings().getFirst().severity()).isEqualTo(ReviewSeverity.CRITICAL);
    }

    @Test
    void derivesRiskFromFindingsWhenMissing() {
        ParsedReview parsed = validator.validate(new RawReview("s", "nonsense",
                List.of(finding("src/A.java", 1, "t", "HIGH", 0.9)), List.of()),
                Set.of("src/A.java"), Map.of(), 10);
        assertThat(parsed.overallRisk()).isEqualTo("HIGH");
    }

    @Test
    void escapesNothingButBoundsText() {
        String longText = "x".repeat(10_000);
        RawReview raw = new RawReview(longText, null,
                List.of(new RawFinding("BUG", "HIGH", "src/A.java", 1, longText, longText, longText, 0.9)),
                List.of());
        ParsedReview parsed = validator.validate(raw, Set.of("src/A.java"), Map.of(), 10);
        assertThat(parsed.summary()).hasSize(2000);
        assertThat(parsed.findings().getFirst().description()).hasSize(4000);
    }
}
