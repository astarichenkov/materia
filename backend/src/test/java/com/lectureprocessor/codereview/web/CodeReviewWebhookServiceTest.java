package com.lectureprocessor.codereview.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lectureprocessor.codereview.CodeReviewJob;
import com.lectureprocessor.codereview.CodeReviewService;
import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.gitea.GiteaClient;
import com.lectureprocessor.support.TestProperties;

/** Webhook validation, idempotency and queueing (no real Gitea/LLM calls). */
class CodeReviewWebhookServiceTest {

    private GiteaClient gitea;
    private CodeReviewService reviews;
    private CodeReviewWebhookService service;
    private GiteaWebhookVerifier verifier;
    private final String secret = "hook-secret";

    private static AppProperties props(String secret) {
        AppProperties base = TestProperties.defaults("./target/test-data");
        return new AppProperties(base.tools(), base.storage(), base.upload(), base.processing(),
                base.ollama(), base.slides(), base.chat(), base.llm(), base.downloader(), base.assistant(),
                base.mcp(), base.deepseek(), base.security(),
                new AppProperties.Gitea("http://gitea:8108", "tok", secret,
                        java.util.List.of("example-org/materia"), Duration.ofSeconds(5), true),
                base.codeReview());
    }

    @BeforeEach
    void setUp() {
        AppProperties properties = props(secret);
        gitea = mock(GiteaClient.class);
        when(gitea.isAllowed("example-org/materia")).thenReturn(true);
        reviews = mock(CodeReviewService.class);
        when(reviews.enqueue(anyString(), anyLong(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new CodeReviewJob(42L, "example-org/materia", 7, "head222", "base111", "cfg",
                        "key", "QUEUED", "webhook", null, null, false, false, false, null, null, null, null,
                        0, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        Instant.now(), null, null));
        verifier = new GiteaWebhookVerifier(properties);
        service = new CodeReviewWebhookService(verifier, new WebhookGuards(), gitea, reviews);
    }

    private byte[] payload(String action) {
        return ("{\"action\":\"" + action + "\",\"repository\":{\"full_name\":\"example-org/materia\"},"
                + "\"pull_request\":{\"number\":7,\"head\":{\"sha\":\"head222\"}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void rejectsInvalidSignature() {
        CodeReviewWebhookService.Outcome outcome =
                service.handle("pull_request", "bad-signature", "d1", payload("opened"));
        assertThat(outcome.status()).isEqualTo(401);
        verify(reviews, never()).enqueue(anyString(), anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void acceptsSignedPullRequestAndQueues() {
        byte[] body = payload("opened");
        String signature = GiteaWebhookVerifier.hmacHex(body, secret);
        CodeReviewWebhookService.Outcome outcome =
                service.handle("pull_request", signature, "d2", body);
        assertThat(outcome.status()).isEqualTo(202);
        assertThat(outcome.jobId()).isEqualTo(42L);
        verify(reviews).enqueue("example-org/materia", 7, "head222", "webhook", false);
    }

    @Test
    void ignoresUnsupportedEventAndAction() {
        byte[] body = payload("opened");
        String signature = GiteaWebhookVerifier.hmacHex(body, secret);
        assertThat(service.handle("push", signature, "d3", body).status()).isEqualTo(202);

        byte[] closed = payload("closed");
        String closedSig = GiteaWebhookVerifier.hmacHex(closed, secret);
        assertThat(service.handle("pull_request", closedSig, "d4", closed).status()).isEqualTo(202);
        verify(reviews, never()).enqueue(anyString(), anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void rejectsRepositoryOutsideAllowList() {
        AppProperties properties = props(secret);
        GiteaClient other = mock(GiteaClient.class);
        when(other.isAllowed(anyString())).thenReturn(false);
        CodeReviewWebhookService scoped = new CodeReviewWebhookService(
                new GiteaWebhookVerifier(properties), new WebhookGuards(), other, reviews);
        byte[] body = payload("opened");
        String signature = GiteaWebhookVerifier.hmacHex(body, secret);
        assertThat(scoped.handle("pull_request", signature, "d5", body).status()).isEqualTo(403);
    }

    @Test
    void secondDeliveryOfSameIdIsIgnored() {
        byte[] body = payload("opened");
        String signature = GiteaWebhookVerifier.hmacHex(body, secret);
        assertThat(service.handle("pull_request", signature, "same-delivery", body).status()).isEqualTo(202);
        assertThat(service.handle("pull_request", signature, "same-delivery", body).status()).isEqualTo(200);
        verify(reviews).enqueue("example-org/materia", 7, "head222", "webhook", false);
    }

    @Test
    void missingSecretRejectsWithServiceUnavailable() {
        AppProperties properties = props("");
        CodeReviewWebhookService noSecret = new CodeReviewWebhookService(
                new GiteaWebhookVerifier(properties), new WebhookGuards(), gitea, reviews);
        assertThat(noSecret.handle("pull_request", "x", "d6", payload("opened")).status()).isEqualTo(503);
    }
}
