package com.lectureprocessor.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates and bounds the model's answer before it is persisted or shown.
 *
 * <p>The prompt asks for a JSON envelope ({@code answer}, {@code suggestedActions},
 * {@code limitations}); parsing is tolerant (markdown fences, plain prose) but the
 * result is always bounded and secret-redacted. When the answer is not grounded
 * in confirmed evidence an explicit limitation is added, so the UI can never
 * present a guess as a fact.</p>
 */
@Component
public class SupportAnswerValidator {

    private static final int MAX_ANSWER = 8000;
    private static final int MAX_ACTION = 400;
    private static final int MAX_LIMITATION = 400;
    private static final int MAX_ACTIONS = 6;
    private static final int MAX_LIMITATIONS = 6;

    private final ObjectMapper objectMapper;

    public SupportAnswerValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidatedAnswer validate(String raw, boolean grounded) {
        if (raw == null || raw.isBlank()) {
            throw new SupportUnavailableException("Модель вернула пустой ответ");
        }
        String answer = raw.strip();
        List<String> actions = new ArrayList<>();
        List<String> limitations = new ArrayList<>();

        String json = extractJson(answer);
        if (json != null) {
            try {
                JsonNode root = objectMapper.readTree(json);
                if (root != null && root.isObject()) {
                    String parsedAnswer = text(root, "answer", "response", "content");
                    if (parsedAnswer == null) {
                        parsedAnswer = text(root, "message");
                    }
                    if (parsedAnswer != null) {
                        answer = parsedAnswer;
                    }
                    actions.addAll(stringList(root.path("suggestedActions")));
                    if (actions.isEmpty()) {
                        actions.addAll(stringList(root.path("actions")));
                    }
                    limitations.addAll(stringList(root.path("limitations")));
                }
            } catch (RuntimeException ignored) {
                // Plain prose answer: keep it as-is.
            }
        }

        String cleanAnswer = bound(SecretRedactor.redact(answer), MAX_ANSWER);
        List<String> cleanActions = clean(actions, MAX_ACTIONS, MAX_ACTION);
        List<String> cleanLimitations = clean(limitations, MAX_LIMITATIONS, MAX_LIMITATION);
        if (!grounded && cleanLimitations.stream().noneMatch(line -> line.toLowerCase().contains("данн"))) {
            cleanLimitations.add(0, "Подтверждённых данных недостаточно — ответ не опирается на факты БД.");
        }
        return new ValidatedAnswer(cleanAnswer, cleanActions, cleanLimitations);
    }

    /** Tolerant extraction of the outermost JSON object; null when absent. */
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
        return null;
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isString() && !value.asString().isBlank()) {
                return value.asString().strip();
            }
        }
        return null;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = item.isString() ? item.asString() : text(item, "text", "title", "action");
                if (value != null && !value.isBlank()) {
                    result.add(value.strip());
                }
            }
        } else if (node.isString() && !node.asString().isBlank()) {
            result.add(node.asString().strip());
        }
        return result;
    }

    private static List<String> clean(List<String> values, int maxItems, int maxChars) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String bounded = bound(SecretRedactor.redact(value), maxChars);
            if (!bounded.isBlank() && seen.add(bounded)) {
                result.add(bounded);
            }
            if (result.size() >= maxItems) {
                break;
            }
        }
        return result;
    }

    private static String bound(String value, int max) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
    }

    /** Validated, bounded answer parts. */
    public record ValidatedAnswer(String answer, List<String> suggestedActions,
            List<String> limitations) {
    }
}
