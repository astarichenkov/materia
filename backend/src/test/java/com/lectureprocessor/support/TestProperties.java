package com.lectureprocessor.support;

import java.time.Duration;
import java.util.Map;

import com.lectureprocessor.config.AppProperties;

/**
 * Builds {@link AppProperties} with small, deterministic values for unit tests.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static AppProperties defaults(String storageRoot) {
        return new AppProperties(
                new AppProperties.Tools("ffmpeg", "ffprobe", "whisper-cli", "model.bin", "ru", Duration.ofMinutes(5)),
                new AppProperties.Storage(storageRoot),
                new AppProperties.Upload(1024L * 1024L),
                new AppProperties.Processing(2, 10, 200, 20, 80, 20, 50),
                new AppProperties.Ollama("http://localhost:11434", "fake-model", "fake-embed", 32,
                        Duration.ofMinutes(1), true),
                new AppProperties.Slides(30.0, 10, 6),
                new AppProperties.Chat(4, 2000, 5, 0.0),
                new AppProperties.Llm(Map.of(
                        "ollama", new AppProperties.Provider(true, "Ollama (local)"),
                        "openai", new AppProperties.Provider(false, "OpenAI (не настроен)"))));
    }
}
