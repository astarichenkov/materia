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
                        "openai", new AppProperties.Provider(false, "OpenAI (не настроен)"),
                        "deepseek", new AppProperties.Provider(true, "DeepSeek (Cloud)")),
                        "ollama", false),
                new AppProperties.Downloader("yt-dlp", Duration.ofSeconds(30), Duration.ofMinutes(5),
                        1024L * 1024L, "720p", false, 1),
                new AppProperties.Assistant(storageRoot, java.util.List.of("README.md", "docs"),
                        1024L * 1024L, 1200, 150, 200, 5, 0.0, Duration.ofSeconds(30), false, 6),
                new AppProperties.Mcp("", 20000),
                new AppProperties.DeepSeek(true, "https://api.deepseek.com", "deepseek-flash",
                        java.util.List.of("deepseek-flash"), Duration.ofSeconds(30), 2, 0.1, 1024,
                        4096, 1, 0.0, new AppProperties.DeepSeek.Pricing(0.0, 0.0, 0.0, "USD")),
                new AppProperties.Security("", storageRoot + "/.secrets/master.key", "LP_MASTER_KEY"),
                new AppProperties.Gitea("http://127.0.0.1:39999", "", "", java.util.List.of("example-org/materia"),
                        Duration.ofSeconds(5), true),
                new AppProperties.CodeReview(true, 120000, 40, 24000, 15, 3, 150, 1200,
                        java.util.List.of("backend/src"), java.util.List.of("**/.env"), 1800, 8, 0.15,
                        false, Duration.ofSeconds(30), false, 60, false));
    }
}
