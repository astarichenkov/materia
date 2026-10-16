package com.lectureprocessor.llm;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.credential.ProviderCredentialService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DeepSeek (cloud) chat provider using the OpenAI-compatible Chat Completions
 * HTTP API. Deliberately implemented on {@link RestClient} (no vendor SDK) so
 * the request shape and error handling stay explicit and testable.
 *
 * <p>Security and correctness properties:</p>
 * <ul>
 *   <li>the API key is read from {@link ProviderCredentialService} (encrypted at
 *       rest) and never logged, never returned, never part of an exception;</li>
 *   <li>the endpoint is fixed to the official HTTPS host (SSRF protection);
 *       loopback is allowed only for local test doubles;</li>
 *   <li>reasoning content ({@code reasoning_content}) is reported separately and
 *       never mixed into the final answer;</li>
 *   <li>errors are classified (auth, balance, rate limit, timeout, ...) so the
 *       UI can react and never sees a raw provider response.</li>
 * </ul>
 *
 * <p>This provider does <b>not</b> implement embeddings: embeddings remain a
 * local Ollama capability ({@link EmbeddingProvider}).</p>
 */
@Service
public class DeepSeekLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_HOSTS = Set.of("api.deepseek.com");
    private static final String CHAT_PATH = "/chat/completions";

    public static final String PROVIDER_ID = "deepseek";

    /** Provider responses that are safe to retry (transient). */
    private static final Set<DeepSeekException.Category> RETRYABLE = Set.of(
            DeepSeekException.Category.RATE_LIMIT,
            DeepSeekException.Category.TIMEOUT,
            DeepSeekException.Category.UNAVAILABLE);

    private final AppProperties properties;
    private final ProviderCredentialService credentials;
    private final RestClient client;
    private final Semaphore permits;

    public DeepSeekLlmProvider(AppProperties properties, ProviderCredentialService credentials) {
        this.properties = properties;
        this.credentials = credentials;
        AppProperties.DeepSeek deepSeek = properties.deepseek();
        Duration timeout = deepSeek.timeout() == null ? Duration.ofSeconds(60) : deepSeek.timeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        int concurrency = Math.max(1, properties.deepseek().maxConcurrentRequests());
        this.permits = new Semaphore(concurrency, true);
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "DeepSeek (Cloud)";
    }

    @Override
    public boolean cloud() {
        return true;
    }

    @Override
    public String defaultChatModel() {
        return properties.deepseek().model();
    }

    @Override
    public boolean isAvailable() {
        // Cheap, no network call: the provider is "available" once configured.
        // Reachability is verified explicitly through testConnection().
        return properties.deepseek().enabled() && credentials.isConfigured(PROVIDER_ID);
    }

    @Override
    public List<LlmModelInfo> listModels() {
        List<String> configured = properties.deepseek().models();
        List<String> names = configured == null || configured.isEmpty()
                ? List.of(properties.deepseek().model()) : configured;
        List<LlmModelInfo> models = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            models.add(new LlmModelInfo(name.strip(), "deepseek", null, null,
                    properties.deepseek().contextWindow() == null ? null
                            : properties.deepseek().contextWindow().longValue(),
                    List.of("completion", "thinking"), null));
        }
        return models;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, LlmOptions options) {
        return generate(systemPrompt, userPrompt, options).content();
    }

    @Override
    public LlmResult generate(String systemPrompt, String userPrompt, LlmOptions options) {
        String apiKey = credentials.resolveKey(PROVIDER_ID).filter(key -> !key.isBlank())
                .orElseThrow(() -> new DeepSeekException(DeepSeekException.Category.MISSING_CREDENTIAL,
                        "DeepSeek API key не настроен. Добавьте ключ в настройках провайдера.", 0));
        String model = options.model() == null || options.model().isBlank()
                ? defaultChatModel() : options.model();
        String endpoint = endpoint();

        String body = JSON.writeValueAsString(buildRequestBody(systemPrompt, userPrompt, model, options));
        boolean acquired = false;
        try {
            // Bounded parallelism: protects the account from accidental bursts.
            permits.acquire();
            acquired = true;
            return callWithRetries(endpoint, apiKey, body, model);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeepSeekException(DeepSeekException.Category.UNAVAILABLE,
                    "Ожидание слота DeepSeek прервано", 503, e);
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    private LlmResult callWithRetries(String endpoint, String apiKey, String body, String model) {
        DeepSeekException last = null;
        int attempts = Math.max(1, properties.deepseek().maxRetries() + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                long start = System.nanoTime();
                String response = call(endpoint, apiKey, body);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                LlmResult result = parseResponse(response, elapsedMs, model);
                credentials.markSuccess(PROVIDER_ID);
                return result;
            } catch (DeepSeekException e) {
                last = e;
                if (!RETRYABLE.contains(e.category()) || attempt == attempts) {
                    credentials.markError(PROVIDER_ID, e.getMessage());
                    throw e;
                }
                log.warn("DeepSeek request failed (category={}, attempt={}/{}); retrying",
                        e.category(), attempt, attempts);
                sleep(backoffMs(attempt));
            }
        }
        throw last == null
                ? new DeepSeekException(DeepSeekException.Category.UNAVAILABLE,
                        "DeepSeek недоступен", 502)
                : last;
    }

    /** Runs a minimal request to verify the configured key works. */
    public TestResult testConnection() {
        try {
            LlmOptions options = new LlmOptions(defaultChatModel(), 0.0, 16, null, null, Boolean.FALSE);
            LlmResult result = generate("You are a connectivity probe.", "Ответь одним словом: ok", options);
            return new TestResult(true, result.content(), result.inputTokens(), result.outputTokens(),
                    result.durationMs(), null);
        } catch (DeepSeekException e) {
            return new TestResult(false, null, 0, 0, 0, e.category() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            return new TestResult(false, null, 0, 0, 0, "UNAVAILABLE: " + e.getMessage());
        }
    }

    private Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt, String model,
            LlmOptions options) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        if (options.temperature() != null) {
            body.put("temperature", options.temperature());
        }
        Integer maxTokens = options.maxTokens() != null ? options.maxTokens()
                : properties.deepseek().maxTokens();
        if (maxTokens != null && maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        // DeepSeek non-thinking mode keeps reasoning out of the answer and the
        // output budget. Only send the switch when the caller expressed intent.
        if (Boolean.FALSE.equals(options.thinking())) {
            body.put("thinking", Map.of("type", "disabled"));
        } else if (Boolean.TRUE.equals(options.thinking())) {
            body.put("thinking", Map.of("type", "enabled"));
        }
        return body;
    }

    private String call(String endpoint, String apiKey, String body) {
        try {
            return client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw classify(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (org.springframework.web.client.ResourceAccessException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("timed out") || message.contains("timeout")) {
                throw new DeepSeekException(DeepSeekException.Category.TIMEOUT,
                        "DeepSeek не ответил за отведённое время", 504, e);
            }
            throw new DeepSeekException(DeepSeekException.Category.UNAVAILABLE,
                    "Не удалось соединиться с DeepSeek", 502, e);
        } catch (RuntimeException e) {
            throw new DeepSeekException(DeepSeekException.Category.UNAVAILABLE,
                    "Ошибка обращения к DeepSeek", 502, e);
        }
    }

    private LlmResult parseResponse(String raw, long elapsedMs, String model) {
        if (raw == null || raw.isBlank()) {
            throw new DeepSeekException(DeepSeekException.Category.MALFORMED_RESPONSE,
                    "DeepSeek вернул пустой ответ", 502);
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (RuntimeException e) {
            throw new DeepSeekException(DeepSeekException.Category.MALFORMED_RESPONSE,
                    "DeepSeek вернул некорректный JSON", 502, e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new DeepSeekException(DeepSeekException.Category.MALFORMED_RESPONSE,
                    "DeepSeek вернул ответ без choices", 502);
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        String content = text(message, "content");
        String reasoning = text(message, "reasoning_content");
        String finishReason = text(choice, "finish_reason");
        boolean truncated = "length".equalsIgnoreCase(finishReason);

        if (content == null || content.isBlank()) {
            if (truncated) {
                throw new DeepSeekException(DeepSeekException.Category.TRUNCATED,
                        "Ответ DeepSeek обрезан: достигнут лимит max_tokens. Увеличьте лимит.", 502);
            }
            throw new DeepSeekException(DeepSeekException.Category.EMPTY_COMPLETION,
                    "DeepSeek вернул пустой ответ", 502);
        }

        JsonNode usage = root.path("usage");
        int inputTokens = intValue(usage, "prompt_tokens");
        int outputTokens = intValue(usage, "completion_tokens");
        int cacheHit = intValue(usage, "prompt_cache_hit_tokens");
        int cacheMiss = intValue(usage, "prompt_cache_miss_tokens");
        int thinkingTokens = LlmProvider.estimateTokens(reasoning);
        double tps = elapsedMs > 0 ? outputTokens / (elapsedMs / 1000.0) : 0;

        log.info("DeepSeek completion model={} finish={} inputTokens={} outputTokens={} cacheHit={} ms={}",
                model, finishReason, inputTokens, outputTokens, cacheHit, elapsedMs);
        return new LlmResult(content.strip(), inputTokens, outputTokens, thinkingTokens, elapsedMs,
                tps, 0, false, truncated, cacheHit, cacheMiss, true);
    }

    /** Maps a provider HTTP failure to a classified, secret-free exception. */
    DeepSeekException classify(int status, String body) {
        String providerMessage = safeProviderMessage(body);
        if (status == 401 || status == 403) {
            return new DeepSeekException(DeepSeekException.Category.AUTHENTICATION,
                    "DeepSeek отклонил API key (401/403). Проверьте ключ.", status);
        }
        if (status == 402) {
            return new DeepSeekException(DeepSeekException.Category.INSUFFICIENT_BALANCE,
                    "Недостаточно средств на балансе DeepSeek (402)", status);
        }
        if (status == 429) {
            return new DeepSeekException(DeepSeekException.Category.RATE_LIMIT,
                    "DeepSeek ограничил частоту запросов (429). Повторите позже.", status);
        }
        if (status == 400 || status == 422) {
            String message = providerMessage == null
                    ? "DeepSeek отклонил запрос как некорректный"
                    : "DeepSeek отклонил запрос: " + providerMessage;
            boolean modelIssue = providerMessage != null
                    && (providerMessage.toLowerCase(Locale.ROOT).contains("model")
                            || providerMessage.toLowerCase(Locale.ROOT).contains("not found"));
            return new DeepSeekException(modelIssue ? DeepSeekException.Category.UNSUPPORTED_MODEL
                    : DeepSeekException.Category.INVALID_REQUEST, message, status);
        }
        if (status == 404) {
            return new DeepSeekException(DeepSeekException.Category.UNSUPPORTED_MODEL,
                    "DeepSeek model/endpoint не найден (404)", status);
        }
        if (status >= 500) {
            return new DeepSeekException(DeepSeekException.Category.UNAVAILABLE,
                    "DeepSeek временно недоступен (" + status + ")", status);
        }
        return new DeepSeekException(DeepSeekException.Category.INVALID_REQUEST,
                "DeepSeek вернул ошибку " + status, status);
    }

    /** Extracts a bounded, secret-free error message from the provider body. */
    static String safeProviderMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode error = JSON.readTree(body).path("error");
            String message = error.path("message").isString() ? error.path("message").asString() : null;
            if (message == null) {
                return null;
            }
            String cleaned = message.replaceAll("\\s+", " ").strip();
            return cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String endpoint() {
        AppProperties.DeepSeek deepSeek = properties.deepseek();
        String base = deepSeek.baseUrl() == null || deepSeek.baseUrl().isBlank()
                ? "https://api.deepseek.com" : deepSeek.baseUrl().strip();
        URI uri = URI.create(base);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = isLoopback(host);
        if (!loopback && !ALLOWED_HOSTS.contains(host)) {
            throw new DeepSeekException(DeepSeekException.Category.CONFIGURATION,
                    "Endpoint DeepSeek запрещён политикой безопасности: " + host
                            + ". Разрешён только официальный api.deepseek.com.", 0);
        }
        if (!loopback && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new DeepSeekException(DeepSeekException.Category.CONFIGURATION,
                    "DeepSeek endpoint должен использовать HTTPS", 0);
        }
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return normalized.endsWith(CHAT_PATH) ? normalized : normalized + CHAT_PATH;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.isString() ? value.asString() : null;
        return text == null || text.isBlank() ? null : text;
    }

    private static int intValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : 0;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long backoffMs(int attempt) {
        return Math.min(2_000L, 250L * (1L << Math.min(attempt, 3)));
    }

    /** Result of an explicit connection test. */
    public record TestResult(boolean ok, String reply, int inputTokens, int outputTokens, long durationMs,
            String error) {
    }

    Optional<String> resolvedKeyForTest() {
        return credentials.resolveKey(PROVIDER_ID);
    }
}
