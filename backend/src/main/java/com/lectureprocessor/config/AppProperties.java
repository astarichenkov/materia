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
        Mcp mcp,
        DeepSeek deepseek,
        Security security,
        Gitea gitea,
        CodeReview codeReview) {

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
     * providers are declared so the benchmark UI can show them as "not configured"
     * until a credential is saved.
     *
     * @param providers         per-provider enable/display metadata
     * @param embeddingProvider provider used for embeddings (always local by default)
     * @param fallbackEnabled   whether an unavailable primary provider may silently
     *                          fall back to Ollama (off by default)
     */
    public record Llm(Map<String, Provider> providers, String embeddingProvider, boolean fallbackEnabled) {
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
     * @param jsRuntime         yt-dlp JavaScript runtime: {@code auto}, {@code node},
     *                          {@code deno} or {@code disabled}. {@code auto} uses the
     *                          first runtime that is actually installed
     * @param jsRuntimePath     optional explicit runtime binary or containing directory
     * @param remoteComponents  explicit opt-in for executable components fetched by
     *                          yt-dlp (e.g. {@code ejs:github}); blank disables it
     * @param cookiesMode       {@code none} (default), {@code browser} or {@code file}
     * @param cookiesBrowser    browser name or user-data path for browser mode
     * @param cookiesBrowserProfile optional profile path/name appended after ':'
     * @param cookiesFile       Netscape cookies file for file mode
     */
    public record Downloader(
            String ytDlpPath,
            Duration metadataTimeout,
            Duration downloadTimeout,
            long maxFileSizeBytes,
            String defaultQuality,
            boolean allowHttp,
            int heavyStagePermits,
            String jsRuntime,
            String jsRuntimePath,
            String remoteComponents,
            String cookiesMode,
            String cookiesBrowser,
            String cookiesBrowserProfile,
            String cookiesFile) {
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

    /**
     * Cloud chat provider (DeepSeek) configuration. Non-secret values only: the
     * API key lives in {@code provider_credential} encrypted at rest and is
     * managed exclusively through the settings UI.
     *
     * @param enabled        whether the provider may be used at all
     * @param baseUrl        official HTTPS API base; custom endpoints are
     *                       restricted to an allow-list (SSRF protection)
     * @param model          default model id
     * @param models         model ids offered in the UI
     * @param timeout        request timeout for a single completion
     * @param maxRetries     bounded number of retries for transient failures
     * @param temperature    default temperature for cloud generation
     * @param maxTokens      default generation budget
     * @param contextWindow  default context window assumption
     * @param maxConcurrentRequests limit of parallel cloud requests (protects budget)
     * @param budgetWarningCost estimated cumulative cost above which mass runs warn
     * @param pricing        configurable per-token pricing used for COST ESTIMATES
     */
    public record DeepSeek(
            boolean enabled,
            String baseUrl,
            String model,
            List<String> models,
            Duration timeout,
            int maxRetries,
            Double temperature,
            Integer maxTokens,
            Integer contextWindow,
            int maxConcurrentRequests,
            double budgetWarningCost,
            Pricing pricing) {

        /**
         * Per-million-token prices for cost ESTIMATES. Values are explicitly
         * configuration, not hardcoded business rules; a deployment must set its
         * own tariff. Costs shown in the UI are always labelled estimates.
         */
        public record Pricing(
                double inputPerMillionTokens,
                double outputPerMillionTokens,
                double cacheHitPerMillionTokens,
                String currency) {
        }
    }

    /**
     * Administrative security for credential management. When {@code adminToken}
     * is blank the credential UI/API is restricted to loopback requests only;
     * setting a token additionally requires it (header, form field or an
     * authenticated admin session) and is the recommended production setup.
     *
     * @param adminToken optional shared secret guarding credential management
     * @param masterKeyPath file that holds the AES-256-GCM master key when no
     *                      external secret store / env variable is used
     * @param masterKeyEnv  name of the environment variable that may supply the
     *                      master key (preferred over the file)
     */
    public record Security(String adminToken, String masterKeyPath, String masterKeyEnv) {
    }

    /**
     * Gitea integration for the automated AI code review (Day 32). Non-secret
     * settings plus two externally supplied secrets: the API token and the
     * webhook HMAC secret. Neither is ever stored in the database or returned by
     * an API.
     *
     * @param baseUrl             Gitea base URL (homelab HTTP or HTTPS)
     * @param token               API token (env {@code GITEA_TOKEN}); header only
     * @param webhookSecret       HMAC-SHA256 webhook secret (env
     *                            {@code GITEA_WEBHOOK_SECRET})
     * @param allowedRepositories explicit allow-list of {@code owner/repo} names
     * @param timeout             per-request timeout
     * @param allowHttp           allow plain HTTP (homelab-only; never public)
     */
    public record Gitea(
            String baseUrl,
            String token,
            String webhookSecret,
            List<String> allowedRepositories,
            Duration timeout,
            boolean allowHttp) {

        public boolean configured() {
            return token != null && !token.isBlank();
        }

        public boolean webhookConfigured() {
            return webhookSecret != null && !webhookSecret.isBlank();
        }
    }

    /**
     * Automated AI code review configuration (Day 32).
     *
     * @param enabled              master switch
     * @param maxDiffChars         hard cap on the diff sent to the model
     * @param maxFiles             hard cap on changed files analysed
     * @param maxContextChars      cap on retrieved code/docs context
     * @param maxFindings          cap on published findings
     * @param maxLlmCalls          cap on model calls per review
     * @param maxCodeIndexFiles    cap on source files indexed per commit
     * @param maxCodeIndexChunks   cap on code chunks stored per commit
     * @param codePaths            project-relative source paths to index
     * @param excludePaths         glob patterns never indexed
     * @param chunkChars           code chunk target size
     * @param retrievalTopK        hybrid code retrievals per review
     * @param minSimilarity        minimum combined similarity for code context
     * @param autoIndex            index related code from Gitea at the head SHA
     * @param timeout              hard timeout for one review run
     * @param pollingEnabled       pull-based fallback when webhooks are unreachable
     * @param pollIntervalSeconds  polling interval
     * @param publish              publish the review as a Gitea PR comment
     */
    public record CodeReview(
            boolean enabled,
            int maxDiffChars,
            int maxFiles,
            int maxContextChars,
            int maxFindings,
            int maxLlmCalls,
            int maxCodeIndexFiles,
            int maxCodeIndexChunks,
            List<String> codePaths,
            List<String> excludePaths,
            int chunkChars,
            int retrievalTopK,
            double minSimilarity,
            boolean autoIndex,
            Duration timeout,
            boolean pollingEnabled,
            int pollIntervalSeconds,
            boolean publish) {
    }
}
