package com.lectureprocessor.codereview.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.support.TestProperties;

/** Webhook HMAC verification. */
class GiteaWebhookVerifierTest {

    private static AppProperties props(String secret) {
        AppProperties base = TestProperties.defaults("./target/test-data");
        return new AppProperties(base.tools(), base.storage(), base.upload(), base.processing(),
                base.ollama(), base.slides(), base.chat(), base.llm(), base.downloader(), base.assistant(),
                base.mcp(), base.deepseek(), base.security(),
                new AppProperties.Gitea("http://gitea:8108", "tok", secret,
                        java.util.List.of("example-org/materia"), Duration.ofSeconds(5), true),
                base.codeReview());
    }

    @Test
    void acceptsCorrectlySignedBody() {
        byte[] body = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        String signature = GiteaWebhookVerifier.hmacHex(body, "s3cret");
        GiteaWebhookVerifier verifier = new GiteaWebhookVerifier(props("s3cret"));
        assertThat(verifier.verify(body, signature)).isTrue();
        assertThat(verifier.verify(body, "sha256=" + signature)).isTrue();
    }

    @Test
    void rejectsWrongSignatureAndMissingSecret() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(new GiteaWebhookVerifier(props("s3cret")).verify(body, "deadbeef")).isFalse();
        assertThat(new GiteaWebhookVerifier(props("s3cret")).verify(body, null)).isFalse();
        assertThat(new GiteaWebhookVerifier(props("")).verify(body, "anything")).isFalse();
    }
}
