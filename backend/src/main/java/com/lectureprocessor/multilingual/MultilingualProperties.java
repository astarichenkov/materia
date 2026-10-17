package com.lectureprocessor.multilingual;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for multilingual lecture processing. Kept separate from
 * {@link com.lectureprocessor.config.AppProperties} so existing tests and
 * components that construct {@code AppProperties} directly are not forced to
 * change (same approach as {@code SupportProperties} / {@code FileAgentProperties}).
 *
 * @param enabled            master switch for the translation stage
 * @param defaultLanguageMode AUTO / ENGLISH / RUSSIAN
 * @param defaultTranslationMode ORIGINAL_ONLY / TRANSLATE_TO_RUSSIAN / BILINGUAL
 * @param targetLanguage     translation target (only ru is supported today)
 * @param autoTranslate      translate English sources automatically by default
 * @param preserveOriginal   always keep the original transcript artifact
 * @param promptTemplate     versioned translation prompt name
 * @param promptVersion      prompt version included in the checkpoint fingerprint
 * @param glossaryVersion    glossary schema/seed version
 * @param maxBatchChars      source characters sent in one translation request
 * @param maxSegmentsPerBatch hard cap of segments per request
 * @param maxRetries         retries for one invalid/failed batch
 * @param maxLlmCalls        hard cap of LLM calls per lecture (cost control)
 * @param tokenBudget        soft token budget for one lecture translation
 * @param timeout            hard timeout for one translation request
 */
@ConfigurationProperties(prefix = "app.translation")
public record MultilingualProperties(
        boolean enabled,
        String defaultLanguageMode,
        String defaultTranslationMode,
        String targetLanguage,
        boolean autoTranslate,
        boolean preserveOriginal,
        String promptTemplate,
        String promptVersion,
        String glossaryVersion,
        int maxBatchChars,
        int maxSegmentsPerBatch,
        int maxRetries,
        int maxLlmCalls,
        int tokenBudget,
        Duration timeout) {

    public static MultilingualProperties defaults() {
        return new MultilingualProperties(true, "AUTO", "TRANSLATE_TO_RUSSIAN", "ru", true, true,
                "lecture-translation-v1", "v3", "v1", 2000, 4, 1, 200, 200_000,
                Duration.ofMinutes(3));
    }
}
