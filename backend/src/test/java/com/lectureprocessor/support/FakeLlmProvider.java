package com.lectureprocessor.support;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import com.lectureprocessor.llm.EmbeddingProvider;
import com.lectureprocessor.llm.LlmModelInfo;
import com.lectureprocessor.llm.LlmOptions;
import com.lectureprocessor.llm.LlmProvider;
import com.lectureprocessor.llm.LlmResult;
import com.lectureprocessor.util.VectorMath;

/**
 * Deterministic in-memory {@link LlmProvider} for tests. Chat responses are
 * configurable; embeddings are bag-of-words vectors so retrieval behaves
 * predictably without any external service. Also records the options and
 * user prompts of every call so tests can assert parameter propagation.
 */
public class FakeLlmProvider implements LlmProvider, EmbeddingProvider {

    private static final int DIMENSIONS = 32;

    private boolean available = true;
    private boolean cloud;
    private String id = "ollama";
    private List<LlmModelInfo> models = List.of();
    private BiFunction<String, String, String> chatFunction =
            (system, user) -> "ANSWER: " + user;
    private boolean detailed = false;
    private int inputTokensPerCall = 10;
    private int outputTokensPerCall = 20;
    private long durationMsPerCall = 5;
    private final List<String> chatCalls = new ArrayList<>();
    private final List<LlmOptions> optionCalls = new ArrayList<>();

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void setCloud(boolean cloud) {
        this.cloud = cloud;
    }

    @Override
    public boolean cloud() {
        return cloud;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setModels(List<LlmModelInfo> models) {
        this.models = models;
    }

    public void setDetailed(boolean detailed) {
        this.detailed = detailed;
    }

    public void setTokenUsage(int inputTokens, int outputTokens) {
        this.inputTokensPerCall = inputTokens;
        this.outputTokensPerCall = outputTokens;
    }

    public void setDurationMsPerCall(long durationMs) {
        this.durationMsPerCall = durationMs;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return "Fake " + id;
    }

    public void setChatFunction(BiFunction<String, String, String> chatFunction) {
        this.chatFunction = chatFunction;
    }

    public List<String> chatCalls() {
        return chatCalls;
    }

    public List<LlmOptions> optionCalls() {
        return optionCalls;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, LlmOptions options) {
        chatCalls.add(userPrompt);
        optionCalls.add(options);
        return chatFunction.apply(systemPrompt, userPrompt);
    }

    @Override
    public LlmResult generate(String systemPrompt, String userPrompt, LlmOptions options) {
        if (!detailed) {
            return LlmProvider.super.generate(systemPrompt, userPrompt, options);
        }
        chatCalls.add(userPrompt);
        optionCalls.add(options);
        String content = chatFunction.apply(systemPrompt, userPrompt);
        return new LlmResult(content, inputTokensPerCall, outputTokensPerCall, 0,
                durationMsPerCall, 100, 0, false);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return texts.stream().map(FakeLlmProvider::embedOne).toList();
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String defaultChatModel() {
        return models.isEmpty() ? "fake-model" : models.getFirst().name();
    }

    @Override
    public List<LlmModelInfo> listModels() {
        return models;
    }

    public static float[] embedOne(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String word : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (!word.isBlank()) {
                vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
            }
        }
        return VectorMath.normalize(vector);
    }
}
