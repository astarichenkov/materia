package com.lectureprocessor.codereview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Validates and normalizes raw model output before anything is published.
 *
 * <p>Guarantees: only known categories/severities, only real changed files, line
 * numbers anchored to the diff when possible, no duplicate findings, bounded
 * text, no empty/speculative findings, and a risk level consistent with the
 * findings.</p>
 */
@Component
public class ReviewValidator {

    private static final double MIN_CONFIDENCE = 0.3;
    private static final int MAX_TITLE = 200;
    private static final int MAX_DESCRIPTION = 4000;
    private static final int MAX_SUGGESTION = 2000;
    private static final int MAX_SUMMARY = 2000;
    private static final int MAX_RECOMMENDATIONS = 10;

    public ParsedReview validate(RawReview raw, Set<String> changedFiles, Map<String, Set<Integer>> anchors,
            int maxFindings) {
        Set<String> normalizedFiles = new LinkedHashSet<>();
        if (changedFiles != null) {
            changedFiles.forEach(file -> normalizedFiles.add(normalizePath(file)));
        }
        List<ReviewFinding> accepted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RawFinding finding : raw.findings()) {
            ReviewFinding validated = validateFinding(finding, normalizedFiles, anchors);
            if (validated == null) {
                continue;
            }
            if (seen.add(validated.dedupKey())) {
                accepted.add(validated);
            }
        }
        accepted.sort(Comparator.comparingInt((ReviewFinding f) -> f.severity().weight()).reversed()
                .thenComparing(f -> -confidence(f)));
        if (accepted.size() > maxFindings) {
            accepted = new ArrayList<>(accepted.subList(0, maxFindings));
        }

        List<String> recommendations = new ArrayList<>();
        Set<String> recommendationSeen = new LinkedHashSet<>();
        for (String recommendation : raw.recommendations()) {
            String clean = sanitize(recommendation, 500);
            if (clean != null && recommendationSeen.add(clean.toLowerCase(Locale.ROOT))) {
                recommendations.add(clean);
            }
        }
        if (recommendations.size() > MAX_RECOMMENDATIONS) {
            recommendations = new ArrayList<>(recommendations.subList(0, MAX_RECOMMENDATIONS));
        }

        String summary = sanitize(raw.summary(), MAX_SUMMARY);
        if (summary == null) {
            summary = accepted.isEmpty()
                    ? "Автоматическое ревью не выявило подтверждённых проблем в изменениях."
                    : "Автоматическое ревью выявило " + accepted.size() + " замечание(й).";
        }
        String risk = normalizeRisk(raw.overallRisk(), accepted);
        return new ParsedReview(summary, risk, accepted, recommendations);
    }

    private ReviewFinding validateFinding(RawFinding raw, Set<String> changedFiles,
            Map<String, Set<Integer>> anchors) {
        String title = sanitize(raw.title(), MAX_TITLE);
        String description = sanitize(raw.description(), MAX_DESCRIPTION);
        if (title == null || description == null) {
            return null;
        }
        double confidence = raw.confidence() == null ? 0.5 : raw.confidence();
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        if (confidence < MIN_CONFIDENCE) {
            return null;
        }
        String file = resolveFile(raw.file(), changedFiles);
        if (file == null) {
            // Hallucinated or unchanged path: do not publish it.
            return null;
        }
        Integer line = raw.line();
        Set<Integer> fileAnchors = anchors == null ? null : anchors.get(file);
        if (fileAnchors != null && !fileAnchors.isEmpty() && (line == null || !fileAnchors.contains(line))) {
            line = null;
        }
        return new ReviewFinding(ReviewCategory.from(raw.category()), ReviewSeverity.from(raw.severity()),
                file, line, title, description, sanitize(raw.suggestion(), MAX_SUGGESTION), confidence);
    }

    private static String resolveFile(String rawFile, Set<String> changedFiles) {
        if (rawFile == null || rawFile.isBlank()) {
            return null;
        }
        String normalized = normalizePath(rawFile);
        if (changedFiles.contains(normalized)) {
            return normalized;
        }
        for (String candidate : changedFiles) {
            if (candidate.equalsIgnoreCase(normalized) || candidate.endsWith("/" + normalized)
                    || normalized.endsWith("/" + candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("a/") || normalized.startsWith("b/")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    static String normalizeRisk(String raw, List<ReviewFinding> findings) {
        String derived = findings.isEmpty() ? "LOW"
                : findings.getFirst().severity().name();
        if (raw == null || raw.isBlank()) {
            return derived;
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LOW", "MEDIUM", "HIGH", "CRITICAL" -> normalized;
            default -> derived;
        };
    }

    private static double confidence(ReviewFinding finding) {
        return finding.confidence() == null ? 0.5 : finding.confidence();
    }

    /** Strips control characters and bounds length; returns null when empty. */
    static String sanitize(String value, int max) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\n\t]]", " ").replaceAll("[ \t]+", " ").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }
}
