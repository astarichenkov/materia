package com.lectureprocessor.codereview;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses the model's structured review JSON. Tolerates markdown fences and a
 * few common field aliases, but never trusts the content: validation is a
 * separate step ({@link ReviewValidator}).
 */
@Component
public class ReviewResultParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    public RawReview parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ReviewParseException("Модель вернула пустой ответ");
        }
        String json = extractJson(raw);
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (RuntimeException e) {
            throw new ReviewParseException("Не удалось разобрать JSON ревью", e);
        }
        if (root == null || !root.isObject()) {
            throw new ReviewParseException("Ответ модели не является JSON-объектом");
        }
        String summary = firstText(root, "summary", "review_summary", "overview");
        String risk = firstText(root, "overallRisk", "overall_risk", "risk");
        List<RawFinding> findings = new ArrayList<>();
        JsonNode findingsNode = root.path("findings");
        if (findingsNode.isArray()) {
            for (JsonNode node : findingsNode) {
                if (!node.isObject()) {
                    continue;
                }
                findings.add(new RawFinding(
                        firstText(node, "category", "type"),
                        firstText(node, "severity", "priority"),
                        firstText(node, "file", "file_path", "path"),
                        integer(node, "line", "line_number", "lineNumber"),
                        firstText(node, "title", "name"),
                        firstText(node, "description", "details", "message"),
                        firstText(node, "suggestion", "fix", "recommendation"),
                        decimal(node, "confidence", "score")));
            }
        }
        List<String> recommendations = new ArrayList<>();
        JsonNode recommendationsNode = root.path("recommendations");
        if (recommendationsNode.isArray()) {
            for (JsonNode node : recommendationsNode) {
                String text = node.isString() ? node.asString() : firstText(node, "text", "description");
                if (text != null && !text.isBlank()) {
                    recommendations.add(text.strip());
                }
            }
        }
        JsonNode singleRecommendation = root.path("recommendation");
        if (singleRecommendation.isString() && !singleRecommendation.asString().isBlank()) {
            recommendations.add(singleRecommendation.asString().strip());
        }
        return new RawReview(summary, risk, findings, recommendations);
    }

    /** Extracts the outermost JSON object, tolerating markdown fences and prose. */
    static String extractJson(String raw) {
        String text = raw.strip();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start >= 0 && end > start) {
                text = text.substring(start + 1, end).strip();
            }
        }
        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return text.substring(first, last + 1);
        }
        return text;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isString() && !value.asString().isBlank()) {
                return value.asString().strip();
            }
        }
        return null;
    }

    private static Integer integer(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isString()) {
                try {
                    return Integer.parseInt(value.asString().strip());
                } catch (NumberFormatException ignored) {
                    // try next field
                }
            }
        }
        return null;
    }

    private static Double decimal(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asDouble();
            }
        }
        return null;
    }
}
