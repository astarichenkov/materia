package com.lectureprocessor.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.DeepSeekException.Category;
import com.lectureprocessor.llm.credential.ProviderCredentialService;
import com.lectureprocessor.support.TestProperties;
import com.sun.net.httpserver.HttpServer;

/**
 * DeepSeek provider tests against a local HTTP test double. No real (paid) API
 * call is ever made. Verifies response parsing, reasoning separation, token
 * metrics, classified error handling and SSRF protection.
 */
class DeepSeekLlmProviderTest {

    private static final String VALID = """
            {"choices":[{"message":{"role":"assistant","content":"Готовый ответ",
              "reasoning_content":"внутренние рассуждения"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":120,"completion_tokens":45,"total_tokens":165,
              "prompt_cache_hit_tokens":20,"prompt_cache_miss_tokens":100}}
            """;

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> nextResponse = new AtomicReference<>(VALID);
    private final AtomicReference<Integer> nextStatus = new AtomicReference<>(200);
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] payload = nextResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextStatus.get(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static AppProperties withEndpoint(AppProperties base, String endpoint, int maxRetries) {
        AppProperties.DeepSeek deepSeek = new AppProperties.DeepSeek(true, endpoint, "deepseek-flash",
                List.of("deepseek-flash"), Duration.ofSeconds(5), maxRetries, 0.1, 512, 4096, 1, 0.0,
                new AppProperties.DeepSeek.Pricing(0.5, 1.5, 0.05, "USD"));
        return new AppProperties(base.tools(), base.storage(), base.upload(), base.processing(),
                base.ollama(), base.slides(), base.chat(), base.llm(), base.downloader(), base.assistant(),
                base.mcp(), deepSeek, base.security());
    }

    private DeepSeekLlmProvider provider(String apiKey, int maxRetries) {
        ProviderCredentialService credentials = mock(ProviderCredentialService.class);
        when(credentials.isConfigured("deepseek")).thenReturn(apiKey != null);
        when(credentials.resolveKey("deepseek")).thenReturn(Optional.ofNullable(apiKey));
        AppProperties base = withEndpoint(TestProperties.defaults("./target/test-data"), baseUrl, maxRetries);
        return new DeepSeekLlmProvider(base, credentials);
    }

    private Category category(DeepSeekLlmProvider provider) {
        try {
            provider.generate("s", "u", LlmOptions.of("deepseek-flash"));
        } catch (DeepSeekException e) {
            return e.category();
        }
        throw new AssertionError("expected DeepSeekException");
    }

    @Test
    void parsesValidResponseWithTokensAndReasoningSeparated() {
        DeepSeekLlmProvider provider = provider("sk-test", 0);
        LlmResult result = provider.generate("system", "user", LlmOptions.of("deepseek-flash"));

        assertThat(result.content()).isEqualTo("Готовый ответ");
        assertThat(result.content()).doesNotContain("рассуждения");
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(45);
        assertThat(result.thinkingTokens()).isPositive();
        assertThat(result.cacheHitTokens()).isEqualTo(20);
        assertThat(result.cacheMissTokens()).isEqualTo(100);
        assertThat(result.cloud()).isTrue();
        assertThat(result.estimated()).isFalse();
        assertThat(lastAuthorization.get()).isEqualTo("Bearer sk-test");
        assertThat(lastBody.get()).contains("\"model\":\"deepseek-flash\"");
        assertThat(lastBody.get()).doesNotContain("sk-test");
    }

    @Test
    void missingCredentialIsReportedClearly() {
        DeepSeekLlmProvider provider = provider(null, 0);
        assertThatThrownBy(() -> provider.generate("s", "u", LlmOptions.of("deepseek-flash")))
                .isInstanceOf(DeepSeekException.class)
                .extracting(e -> ((DeepSeekException) e).category())
                .isEqualTo(Category.MISSING_CREDENTIAL);
        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void invalidApiKeyIsAuthenticationError() {
        nextStatus.set(401);
        nextResponse.set("{\"error\":{\"message\":\"Authentication Fails\"}}");
        assertThat(category(provider("sk-bad", 0))).isEqualTo(Category.AUTHENTICATION);
    }

    @Test
    void insufficientBalanceIsClassified() {
        nextStatus.set(402);
        nextResponse.set("{\"error\":{\"message\":\"Insufficient Balance\"}}");
        assertThat(category(provider("sk-test", 0))).isEqualTo(Category.INSUFFICIENT_BALANCE);
    }

    @Test
    void rateLimitIsClassified() {
        nextStatus.set(429);
        nextResponse.set("{\"error\":{\"message\":\"rate limit\"}}");
        assertThat(category(provider("sk-test", 0))).isEqualTo(Category.RATE_LIMIT);
    }

    @Test
    void providerUnavailableOnServerError() {
        nextStatus.set(503);
        nextResponse.set("{\"error\":{\"message\":\"boom\"}}");
        assertThat(category(provider("sk-test", 0))).isEqualTo(Category.UNAVAILABLE);
    }

    @Test
    void invalidModelIsClassified() {
        nextStatus.set(400);
        nextResponse.set("{\"error\":{\"message\":\"Model Not Exist: deepseek-nope\"}}");
        assertThat(category(provider("sk-test", 0))).isEqualTo(Category.UNSUPPORTED_MODEL);
    }

    @Test
    void malformedJsonIsClassified() {
        nextResponse.set("not-json");
        assertThat(category(provider("sk-test", 0))).isEqualTo(Category.MALFORMED_RESPONSE);
    }

    @Test
    void emptyCompletionWithLengthIsTruncated() {
        nextResponse.set("{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"length\"}]}");
        assertThat(category(provider("sk-test", 0))).isEqualTo(Category.TRUNCATED);
    }

    @Test
    void emptyCompletionWithoutLengthIsEmpty() {
        nextResponse.set("{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}");
        assertThat(category(provider("sk-test", 0))).isEqualTo(Category.EMPTY_COMPLETION);
    }

    @Test
    void rejectsNonAllowlistedEndpoint() {
        ProviderCredentialService credentials = mock(ProviderCredentialService.class);
        when(credentials.resolveKey("deepseek")).thenReturn(Optional.of("sk-test"));
        AppProperties props = withEndpoint(TestProperties.defaults("./target/test-data"),
                "https://evil.example.com", 0);
        DeepSeekLlmProvider provider = new DeepSeekLlmProvider(props, credentials);

        assertThatThrownBy(() -> provider.generate("s", "u", LlmOptions.of("deepseek-flash")))
                .isInstanceOf(DeepSeekException.class)
                .extracting(e -> ((DeepSeekException) e).category())
                .isEqualTo(Category.CONFIGURATION);
    }

    @Test
    void availabilityReflectsCredentialConfiguration() {
        ProviderCredentialService configured = mock(ProviderCredentialService.class);
        when(configured.isConfigured("deepseek")).thenReturn(true);
        DeepSeekLlmProvider provider = new DeepSeekLlmProvider(
                TestProperties.defaults("./target/test-data"), configured);
        assertThat(provider.isAvailable()).isTrue();
        assertThat(provider.cloud()).isTrue();
        assertThat(provider.id()).isEqualTo("deepseek");
        assertThat(provider.listModels()).isNotEmpty();
    }
}
