package com.lectureprocessor.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.util.VectorMath;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real local LLM integration backed by Ollama through Spring AI.
 *
 * <p>Models are constructed explicitly from configuration rather than relying on
 * auto-configuration, so the application starts (and tests run) even when Ollama
 * is not running.
 */
@Service
public class OllamaLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AppProperties properties;
    private final OllamaApi ollamaApi;
    private final OllamaChatModel chatModel;
    private final OllamaEmbeddingModel embeddingModel;
    private final RestClient healthClient;
    private final boolean disableThinking;

    public OllamaLlmProvider(AppProperties properties) {
        this.properties = properties;
        AppProperties.Ollama ollama = properties.ollama();
        // Connect quickly, allow long generation, and never retry on connection
        // failure: an unreachable Ollama must fail fast, not hang for minutes.
        SimpleClientHttpRequestFactory ollamaHttp = new SimpleClientHttpRequestFactory();
        ollamaHttp.setConnectTimeout(Duration.ofSeconds(5));
        ollamaHttp.setReadTimeout(toMillis(ollama.timeout()));
        RetryTemplate noRetry = new RetryTemplate(RetryPolicy.withMaxRetries(0));
        this.ollamaApi = OllamaApi.builder()
                .baseUrl(ollama.baseUrl())
                .restClientBuilder(RestClient.builder().requestFactory(ollamaHttp))
                .build();
        this.disableThinking = ollama.disableThinking();
        OllamaChatOptions.Builder defaultOptions = OllamaChatOptions.builder()
                .model(ollama.chatModel())
                .numCtx(8192);
        if (disableThinking) {
            defaultOptions.disableThinking();
        }
        this.chatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(defaultOptions.build())
                .retryTemplate(noRetry)
                .build();
        this.embeddingModel = OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .options(OllamaEmbeddingOptions.builder()
                        .model(ollama.embeddingModel())
                        .build())
                .build();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.healthClient = RestClient.builder()
                .baseUrl(ollama.baseUrl())
                .requestFactory(factory)
                .build();
    }

    private static int toMillis(Duration duration) {
        long millis = duration == null ? Duration.ofMinutes(10).toMillis() : duration.toMillis();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, millis));
    }

    @Override
    public String id() {
        return LlmRunConfig.OLLAMA;
    }

    @Override
    public String displayName() {
        return "Ollama (local)";
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, LlmOptions options) {
        return generate(systemPrompt, userPrompt, options).content();
    }

    @Override
    public LlmResult generate(String systemPrompt, String userPrompt, LlmOptions options) {
        try {
            List<OllamaApi.Message> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(OllamaApi.Message.builder(OllamaApi.Message.Role.SYSTEM)
                        .content(systemPrompt).build());
            }
            messages.add(OllamaApi.Message.builder(OllamaApi.Message.Role.USER)
                    .content(userPrompt).build());

            Map<String, Object> requestOptions = new HashMap<>();
            requestOptions.put("temperature", options.temperature());
            requestOptions.put("num_ctx", options.numCtx());
            requestOptions.put("num_predict", options.maxTokens());
            requestOptions.put("seed", options.seed());

            boolean thinking = options.thinking() != null ? options.thinking() : !disableThinking;
            OllamaApi.ChatRequest.Builder request = OllamaApi.ChatRequest.builder(options.model())
                    .messages(messages)
                    .stream(false)
                    .options(requestOptions);
            if (thinking) {
                request.enableThinking();
            } else {
                request.disableThinking();
            }

            long start = System.nanoTime();
            OllamaApi.ChatResponse response = ollamaApi.chat(request.build());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            String content = response.message() == null ? null : response.message().content();
            if (content == null) {
                throw new LlmException("Ollama вернула пустой ответ");
            }
            String thinkingText = response.message().thinking();
            int inputTokens = response.promptEvalCount() == null ? 0 : response.promptEvalCount();
            int outputTokens = response.evalCount() == null ? 0 : response.evalCount();
            long evalNanos = response.evalDuration() == null ? 0 : response.evalDuration();
            double tps = evalNanos > 0 ? outputTokens / (evalNanos / 1_000_000_000.0) : 0;
            long loadMs = response.loadDuration() == null ? 0 : response.loadDuration() / 1_000_000;
            return new LlmResult(sanitize(content), inputTokens, outputTokens,
                    LlmProvider.estimateTokens(thinkingText), elapsedMs, tps, loadMs, false,
                    "length".equalsIgnoreCase(response.doneReason()));
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Ошибка обращения к Ollama (" + options.model() + "): " + e.getMessage(), e);
        }
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        try {
            return embeddingModel.embed(texts).stream().map(VectorMath::normalize).toList();
        } catch (Exception e) {
            throw new LlmException("Ошибка вычисления эмбеддингов: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            healthClient.get().uri("/api/tags").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.debug("Ollama not available at {}: {}", properties.ollama().baseUrl(), e.getMessage());
            return false;
        }
    }

    @Override
    public String defaultChatModel() {
        return properties.ollama().chatModel();
    }

    @Override
    public List<LlmModelInfo> listModels() {
        try {
            String body = healthClient.get().uri("/api/tags").retrieve().body(String.class);
            if (body == null) {
                return List.of();
            }
            JsonNode root = JSON.readTree(body);
            List<LlmModelInfo> models = new ArrayList<>();
            for (JsonNode node : root.path("models")) {
                JsonNode details = node.path("details");
                List<String> capabilities = new ArrayList<>();
                for (JsonNode capability : node.path("capabilities")) {
                    capabilities.add(capability.asString());
                }
                models.add(new LlmModelInfo(
                        text(node, "name"),
                        text(details, "family"),
                        text(details, "parameter_size"),
                        text(details, "quantization_level"),
                        details.has("context_length") && !details.get("context_length").isNull()
                                ? details.get("context_length").asLong() : null,
                        capabilities,
                        node.has("size") && !node.get("size").isNull() ? node.get("size").asLong() : null));
            }
            return models;
        } catch (Exception e) {
            log.debug("Cannot list Ollama models: {}", e.getMessage());
            return List.of();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asString().isBlank() ? null : value.asString();
    }

    /** Removes Qwen3 thinking blocks if the model emits them. */
    static String sanitize(String content) {
        return content.replaceAll("(?s) thinking.*?<｜end▁of▁thinking｜>", "").strip();
    }
}
