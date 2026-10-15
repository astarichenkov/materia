package com.lectureprocessor.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Central application configuration. All machine-specific values (binary paths,
 * storage locations, limits) live here and are supplied via application.yml or
 * environment variables — never hardcoded in business logic.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Tools tools,
        Storage storage,
        Upload upload,
        Processing processing,
        Ollama ollama,
        Slides slides,
        Chat chat,
        Llm llm,
        Downloader downloader) {

    public record Tools(
            String ffmpegPath,
            String ffprobePath,
            String whisperPath,
            String whisperModelPath,
            String whisperLanguage,
            Duration timeout) {
    }

    public record Storage(String root) {
    }

    public record Upload(long maxFileSizeBytes) {
    }

    public record Processing(
            int workerThreads,
            int queueCapacity,
            int llmChunkChars,
            int llmChunkOverlapChars,
            int ragChunkChars,
            int ragChunkOverlapChars,
            int maxChunksPerLecture) {
    }

    public record Ollama(
            String baseUrl,
            String chatModel,
            String embeddingModel,
            int embeddingDimensions,
            Duration timeout,
            boolean disableThinking) {
    }

    public record Slides(
            double intervalSeconds,
            int maxImages,
            int hashDistanceThreshold) {
    }

    public record Chat(
            int maxHistoryMessages,
            int maxQuestionChars,
            int retrievalTopK,
            double minSimilarity) {
    }

    /**
     * LLM provider declarations. Local providers are backed by real beans; cloud
     * providers are declared (disabled by default) so the benchmark UI can show
     * them as "not configured" without shipping a cloud client yet.
     */
    public record Llm(Map<String, Provider> providers) {
    }

    public record Provider(boolean enabled, String displayName) {
    }

    /**
     * Remote media ingestion (yt-dlp) limits and safety switches.
     *
     * @param ytDlpPath        yt-dlp executable (or `python -m yt_dlp` via a wrapper)
     * @param metadataTimeout   timeout for a metadata probe
     * @param downloadTimeout   hard timeout for a single download
     * @param maxFileSizeBytes  maximum downloaded media size
     * @param defaultQuality    quality used when the request does not choose one
     * @param allowHttp         allow plain-HTTP source URLs (disabled by default)
     * @param heavyStagePermits max concurrent CPU/GPU-heavy stages (whisper + LLM)
     */
    public record Downloader(
            String ytDlpPath,
            Duration metadataTimeout,
            Duration downloadTimeout,
            long maxFileSizeBytes,
            String defaultQuality,
            boolean allowHttp,
            int heavyStagePermits) {
    }
}
