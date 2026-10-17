package com.lectureprocessor.fileagent;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.lectureprocessor.fileagent.model.ChangeProposalDraft;
import com.lectureprocessor.fileagent.model.ProposedChangeDraft;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses model output into a proposal draft. Models sometimes wrap JSON in
 * markdown fences or add prose; only the first complete JSON object is used and
 * every field is validated later by {@link FileAgentPatchValidator}.
 */
@Component
public class FileAgentProposalParser {

    private final ObjectMapper objectMapper;

    public FileAgentProposalParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ChangeProposalDraft parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new FileAgentException("Модель вернула пустое предложение");
        }
        String json = extractJson(raw);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (RuntimeException e) {
            throw new FileAgentException("Не удалось разобрать JSON предложения: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new FileAgentException("Предложение должно быть JSON-объектом");
        }
        String summary = text(root, "summary");
        List<ProposedChangeDraft> changes = new ArrayList<>();
        JsonNode array = root.get("changes");
        if (array == null || !array.isArray() || array.isEmpty()) {
            throw new FileAgentException("Предложение не содержит изменений");
        }
        for (JsonNode node : array) {
            if (!node.isObject()) {
                continue;
            }
            changes.add(new ProposedChangeDraft(text(node, "path"), text(node, "action"),
                    text(node, "content"), text(node, "reason")));
        }
        if (changes.isEmpty()) {
            throw new FileAgentException("Предложение не содержит валидных изменений");
        }
        List<String> notes = new ArrayList<>();
        JsonNode notesNode = root.get("notes");
        if (notesNode != null && notesNode.isArray()) {
            notesNode.forEach(item -> {
                if (item != null && item.isString() && !item.asText().isBlank()) {
                    notes.add(item.asText());
                }
            });
        }
        return new ChangeProposalDraft(summary, changes, notes);
    }

    static String extractJson(String raw) {
        String text = raw.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).strip();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new FileAgentException("В ответе модели нет JSON-объекта");
        }
        return sanitizeControlChars(text.substring(start, end + 1));
    }

    /**
     * Small local models frequently emit raw newlines/tabs inside JSON strings.
     * Escape control characters that occur inside string literals so a valid
     * document can still be parsed; structural JSON is left untouched.
     */
    static String sanitizeControlChars(String json) {
        StringBuilder out = new StringBuilder(json.length() + 32);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!inString) {
                if (c == '"') {
                    inString = true;
                }
                out.append(c);
                continue;
            }
            if (escaped) {
                out.append(c);
                escaped = false;
                continue;
            }
            if (c == (char) 92) {
                out.append(c);
                escaped = true;
            } else if (c == '"') {
                inString = false;
                out.append(c);
            } else if (c == (char) 10) {
                out.append((char) 92).append('n');
            } else if (c == (char) 13) {
                out.append((char) 92).append('r');
            } else if (c == (char) 9) {
                out.append((char) 92).append('t');
            } else if (c < 0x20) {
                out.append((char) 92).append('u').append(String.format("%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isString() ? value.asText() : String.valueOf(value);
    }
}
