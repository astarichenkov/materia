package com.lectureprocessor.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.support.TestProperties;

/**
 * Verifies that an unreachable Ollama fails fast with a clear domain error
 * instead of hanging on connection retries (real regression found during the
 * Day 27 integration run).
 */
class OllamaLlmProviderTest {

    // Port 59987 is assumed closed in the test environment.
    private static final String CLOSED = "http://127.0.0.1:59987";

    private static OllamaLlmProvider provider() {
        AppProperties base = TestProperties.defaults("./target/test-data");
        AppProperties props = new AppProperties(
                base.tools(), base.storage(), base.upload(), base.processing(),
                new AppProperties.Ollama(CLOSED, "qwen3:4b", "nomic-embed-text", 768, Duration.ofSeconds(2), true),
                base.slides(), base.chat(), base.llm(), base.downloader(),
                base.assistant(), base.mcp());
        return new OllamaLlmProvider(props);
    }

    @Test
    void reportsUnavailableWhenOllamaDown() {
        assertThat(provider().isAvailable()).isFalse();
    }

    @Test
    void chatFailsFastWhenOllamaUnreachable() {
        OllamaLlmProvider provider = provider();
        long start = System.nanoTime();

        assertThatThrownBy(() -> provider.chat("system", "user", LlmOptions.of("qwen3:4b")))
                .isInstanceOf(LlmException.class);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs)
                .as("unreachable Ollama must fail fast (was: minutes-long retry loop)")
                .isLessThan(15_000);
    }
}
