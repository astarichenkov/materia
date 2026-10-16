package com.lectureprocessor.config;

import java.time.Duration;
import java.util.List;
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
        Downloader downloader,
        Assistant assistant,
        Mcp mcp) {

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

    /**
     * Developer assistant (Day 31) configuration.
     *
     * @param projectRoot        explicit project root; when blank it is detected
     *                           by walking up from the working directory
     * @param docsPaths          project-relative documentation paths (files or
     *                           directories) indexed into the documentation RAG
     * @param maxFileBytes       maximum size of a documentation file to index
     * @param chunkChars         documentation chunk target size
     * @param chunkOverlapChars  documentation chunk overlap
     * @param maxChunksPerDoc    safety cap on chunks per document
     * @param retrievalTopK      documentation snippets retrieved per question
     * @param minSimilarity      minimum cosine similarity for a docs snippet
     * @param timeout            hard timeout for a single assistant answer
     * @param autoIndex          index documentation on startup when the local
     *                           embedding model is reachable
     * @param historyMessages    recent dialogue turns kept per assistant session
     */
    public record Assistant(
            String projectRoot,
            List<String> docsPaths,
            long maxFileBytes,
            int chunkChars,
            int chunkOverlapChars,
            int maxChunksPerDoc,
            int retrievalTopK,
            double minSimilarity,
            Duration timeout,
            boolean autoIndex,
            int historyMessages) {
    }

    /**
     * MCP server configuration. The endpoint is read-only and local by default;
     * setting {@code apiKey} requires the {@code X-MCP-Key} header on requests.
     *
     * @param apiKey         optional shared secret; blank disables the check
     * @param maxOutputChars hard cap on any tool output returned to a client
     */
    public record Mcp(String apiKey, int maxOutputChars) {
    }
}
