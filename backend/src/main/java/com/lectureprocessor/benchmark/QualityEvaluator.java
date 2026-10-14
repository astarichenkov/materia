package com.lectureprocessor.benchmark;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Deterministic quality rubric for benchmark outputs. Five criteria are scored
 * 0–5 and averaged:
 *
 * <ol>
 *   <li>completeness — output is non-trivial;</li>
 *   <li>fact preservation — required facts/keywords are present;</li>
 *   <li>no hallucination — forbidden terms are absent;</li>
 *   <li>structure — headings/lists/paragraphs;</li>
 *   <li>grammar — plausible natural-language output (heuristic).</li>
 * </ol>
 *
 * Numbers are required when the fixture lists them. Nothing is scored by an LLM,
 * so results are reproducible without a model.
 */
@Component
public class QualityEvaluator {

    public QualityResult evaluate(String output, QualitySpec spec) {
        List<String> notes = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return new QualityResult(0.0, List.of("пустой ответ"));
        }
        String lower = output.toLowerCase().replace('ё', 'е');
        QualitySpec safe = spec == null ? QualitySpec.empty() : spec;

        double completeness = scoreCompleteness(output, notes);
        double facts = scoreFacts(lower, safe, notes);
        double noHallucination = scoreHallucination(lower, safe, notes);
        double structure = scoreStructure(output, notes);
        double grammar = scoreGrammar(output, notes);

        double average = (completeness + facts + noHallucination + structure + grammar) / 5.0;
        return new QualityResult(Math.round(average * 10.0) / 10.0, notes);
    }

    private static double scoreCompleteness(String output, List<String> notes) {
        int length = output.strip().length();
        if (length >= 80) {
            notes.add("completeness=5");
            return 5;
        }
        if (length >= 20) {
            notes.add("completeness=3 (короткий ответ)");
            return 3;
        }
        notes.add("completeness=1 (слишком короткий ответ)");
        return 1;
    }

    private static double scoreFacts(String lower, QualitySpec spec, List<String> notes) {
        if (spec.requiredFacts().isEmpty()) {
            notes.add("facts=n/a");
            return 5;
        }
        long hits = spec.requiredFacts().stream()
                .filter(f -> f != null && !f.isBlank() && lower.contains(f.toLowerCase().replace('ё', 'е')))
                .count();
        double score = 5.0 * hits / spec.requiredFacts().size();
        long numbers = spec.requiredNumbers().stream().filter(lower::contains).count();
        if (!spec.requiredNumbers().isEmpty()) {
            score = (score + 5.0 * numbers / spec.requiredNumbers().size()) / 2.0;
        }
        notes.add("facts=" + hits + "/" + spec.requiredFacts().size()
                + (spec.requiredNumbers().isEmpty() ? ""
                        : ", numbers=" + numbers + "/" + spec.requiredNumbers().size()));
        return round(score);
    }

    private static double scoreHallucination(String lower, QualitySpec spec, List<String> notes) {
        List<String> found = spec.forbiddenTerms().stream()
                .filter(t -> t != null && !t.isBlank() && lower.contains(t.toLowerCase().replace('ё', 'е')))
                .toList();
        if (found.isEmpty()) {
            notes.add("hallucination=ok");
            return 5;
        }
        notes.add("hallucination: " + String.join(", ", found));
        return 0;
    }

    private static double scoreStructure(String output, List<String> notes) {
        boolean headings = output.contains("##") || output.contains("# ");
        boolean list = output.contains("\n- ") || output.contains("\n* ") || output.contains("\n1.");
        if (headings && list) {
            notes.add("structure=5");
            return 5;
        }
        if (headings || list) {
            notes.add("structure=4");
            return 4;
        }
        if (output.contains("\n")) {
            notes.add("structure=3");
            return 3;
        }
        notes.add("structure=2");
        return 2;
    }

    private static double scoreGrammar(String output, List<String> notes) {
        long letters = output.codePoints().filter(Character::isLetter).count();
        long control = output.codePoints().filter(c -> c < 0x20 && c != '\n' && c != '\t' && c != '\r').count();
        if (letters < 10 || control > 0) {
            notes.add("grammar=2");
            return 2;
        }
        boolean hasCyrillic = output.codePoints().anyMatch(c -> c >= 0x0400 && c <= 0x04FF);
        boolean hasLatin = output.codePoints().anyMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'));
        notes.add("grammar=" + (hasCyrillic || hasLatin ? 5 : 3));
        return hasCyrillic || hasLatin ? 5 : 3;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
