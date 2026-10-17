package com.lectureprocessor.multilingual;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.lectureprocessor.domain.Transcript;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Determines the spoken language from information Whisper already produced.
 *
 * <p>whisper.cpp with {@code -l auto} writes the detected language into its JSON
 * ({@code result.language} and/or {@code params.language}). No extra LLM call is
 * made. Confidence is only filled when the tool actually reports it — the
 * application never invents a confidence value.</p>
 *
 * <p>When the JSON carries no language (older builds, forced language), a cheap
 * script heuristic is used and the source is recorded as {@link
 * LanguageDetectionSource#HEURISTIC}.</p>
 */
@Component
public class LanguageDetector {

    private final ObjectMapper objectMapper;

    public LanguageDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves language metadata for a transcript.
     *
     * @param rawJson       untouched whisper.cpp JSON (may be null)
     * @param transcript    sanitized transcript used as a heuristic fallback
     * @param requestedMode user-selected mode
     */
    public Detection detect(String rawJson, Transcript transcript, LanguageMode requestedMode) {
        if (requestedMode.explicit()) {
            return new Detection(requestedMode.whisperCode(), null,
                    LanguageDetectionSource.MANUAL);
        }
        JsonNode root = parse(rawJson);
        String reported = readLanguage(root);
        if (reported != null && !reported.isBlank() && !"auto".equalsIgnoreCase(reported)) {
            Double confidence = readConfidence(root);
            return new Detection(normalize(reported), confidence,
                    LanguageDetectionSource.WHISPER_DETECTED);
        }
        String heuristic = heuristic(transcript);
        return new Detection(heuristic, null, LanguageDetectionSource.HEURISTIC);
    }

    private JsonNode parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String readLanguage(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode result = root.path("result");
        if (result.hasNonNull("language")) {
            return result.get("language").asText();
        }
        JsonNode params = root.path("params");
        if (params.hasNonNull("language")) {
            return params.get("language").asText();
        }
        if (root.hasNonNull("language")) {
            return root.get("language").asText();
        }
        return null;
    }

    private static Double readConfidence(JsonNode root) {
        if (root == null) {
            return null;
        }
        for (String key : new String[] {"language_probability", "language_confidence"}) {
            JsonNode result = root.path("result");
            if (result.hasNonNull(key) && result.get(key).isNumber()) {
                return result.get(key).asDouble();
            }
            if (root.hasNonNull(key) && root.get(key).isNumber()) {
                return root.get(key).asDouble();
            }
        }
        return null;
    }

    /** Script-based fallback: Cyrillic vs Latin letters over the whole transcript. */
    static String heuristic(Transcript transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return "und";
        }
        long cyrillic = 0;
        long latin = 0;
        for (var segment : transcript.segments()) {
            for (int i = 0; i < segment.text().length(); i++) {
                char c = segment.text().charAt(i);
                if (c >= 'А' && c <= 'я' || c == 'Ё' || c == 'ё') {
                    cyrillic++;
                } else if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') {
                    latin++;
                }
            }
        }
        long total = cyrillic + latin;
        if (total == 0) {
            return "und";
        }
        if (cyrillic * 100 / total >= 60) {
            return "ru";
        }
        if (latin * 100 / total >= 60) {
            return "en";
        }
        return "und";
    }

    private static String normalize(String language) {
        String value = language.strip().toLowerCase(Locale.ROOT);
        if (value.length() > 2) {
            // whisper.cpp may report "english"/"russian"
            return switch (value) {
                case "english" -> "en";
                case "russian" -> "ru";
                default -> value.substring(0, 2);
            };
        }
        return value;
    }

    /**
     * @param language   ISO 639-1 code (or {@code und})
     * @param confidence provider-reported confidence or null (never invented)
     * @param source     detection source
     */
    public record Detection(String language, Double confidence, LanguageDetectionSource source) {
    }
}
