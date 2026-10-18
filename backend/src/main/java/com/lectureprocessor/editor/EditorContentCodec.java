package com.lectureprocessor.editor;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.multilingual.SegmentTranslationStatus;
import com.lectureprocessor.multilingual.TranslationSegment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Serializes editor content to/from the revision store. Explicit DTOs are used
 * so persisted revisions are stable even if runtime domain records gain fields.
 */
@Component
public class EditorContentCodec {

    private final ObjectMapper objectMapper;

    public EditorContentCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encodeTranscript(List<TranscriptSegment> segments) {
        ArrayNode array = objectMapper.createArrayNode();
        for (TranscriptSegment segment : segments) {
            ObjectNode node = array.addObject();
            node.put("sequence", segment.sequence());
            node.put("startMs", segment.startMs());
            node.put("endMs", segment.endMs());
            node.put("text", segment.text());
        }
        return objectMapper.writeValueAsString(array);
    }

    public List<TranscriptSegment> decodeTranscript(String content) {
        List<TranscriptSegment> result = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return result;
        }
        for (JsonNode node : objectMapper.readTree(content)) {
            result.add(new TranscriptSegment(
                    node.path("sequence").asInt(),
                    node.path("startMs").asLong(),
                    node.path("endMs").asLong(),
                    node.path("text").asText("")));
        }
        return result;
    }

    public String encodeTranslation(List<TranslationSegment> segments) {
        ArrayNode array = objectMapper.createArrayNode();
        for (TranslationSegment segment : segments) {
            ObjectNode node = array.addObject();
            node.put("sequence", segment.sequence());
            node.put("startMs", segment.startMs());
            node.put("endMs", segment.endMs());
            node.put("sourceText", segment.sourceText());
            node.put("translatedText", segment.translatedText());
            node.put("status", segment.status().name());
            node.put("quality", segment.quality());
            node.put("warning", segment.warning());
            node.put("error", segment.error());
            node.put("attempts", segment.attempts());
        }
        return objectMapper.writeValueAsString(array);
    }

    public List<TranslationSegment> decodeTranslation(String content) {
        List<TranslationSegment> result = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return result;
        }
        for (JsonNode node : objectMapper.readTree(content)) {
            result.add(new TranslationSegment(
                    node.path("sequence").asInt(),
                    node.path("startMs").asLong(),
                    node.path("endMs").asLong(),
                    node.path("sourceText").asText(""),
                    node.path("translatedText").isNull()
                            ? null : node.path("translatedText").asText(null),
                    parseStatus(node.path("status").asText("PENDING")),
                    node.path("quality").isNull() ? "OK" : node.path("quality").asText("OK"),
                    node.path("warning").isNull() ? null : node.path("warning").asText(null),
                    node.path("error").isNull() ? null : node.path("error").asText(null),
                    node.path("attempts").asInt(0)));
        }
        return result;
    }

    private static SegmentTranslationStatus parseStatus(String value) {
        try {
            return SegmentTranslationStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return SegmentTranslationStatus.PENDING;
        }
    }
}
