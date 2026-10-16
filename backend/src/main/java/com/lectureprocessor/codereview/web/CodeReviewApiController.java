package com.lectureprocessor.codereview.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lectureprocessor.codereview.CodeReviewJob;
import com.lectureprocessor.codereview.CodeReviewService;
import com.lectureprocessor.codereview.ReviewResult;
import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.gitea.GiteaClient;

/**
 * Code-review REST API. The webhook endpoint is HMAC-protected and public (Gitea
 * must reach it); the admin endpoints are guarded by
 * {@code AdminSecurityFilter}.
 */
@RestController
@RequestMapping("/api/code-review")
public class CodeReviewApiController {

    private static final int MAX_WEBHOOK_BYTES = 1_000_000;

    private final CodeReviewService reviews;
    private final CodeReviewWebhookService webhookService;
    private final GiteaClient gitea;
    private final AppProperties properties;

    public CodeReviewApiController(CodeReviewService reviews, CodeReviewWebhookService webhookService,
            GiteaClient gitea, AppProperties properties) {
        this.reviews = reviews;
        this.webhookService = webhookService;
        this.gitea = gitea;
        this.properties = properties;
    }

    @PostMapping(path = "/webhook/gitea", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestHeader(value = "X-Gitea-Event", required = false) String event,
            @RequestHeader(value = "X-Gitea-Signature", required = false) String signature,
            @RequestHeader(value = "X-Gitea-Delivery", required = false) String delivery,
            @RequestBody(required = false) byte[] body) {
        if (body == null || body.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty body"));
        }
        if (body.length > MAX_WEBHOOK_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "payload too large"));
        }
        CodeReviewWebhookService.Outcome outcome = webhookService.handle(event, signature, delivery, body);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", outcome.status());
        response.put("message", outcome.message());
        if (outcome.jobId() != null) {
            response.put("jobId", outcome.jobId());
        }
        if (outcome.repository() != null) {
            response.put("repository", outcome.repository());
        }
        return ResponseEntity.status(outcome.status()).body(response);
    }

    @GetMapping
    public List<Map<String, Object>> history() {
        return reviews.history(50).stream().map(CodeReviewApiController::summary).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        CodeReviewJob job = reviews.get(id);
        Map<String, Object> view = summary(job);
        view.put("findings", reviews.findings(id));
        return view;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> manual(@RequestBody ManualReviewRequest request) {
        if (request == null || request.repository() == null || request.prNumber() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "repository и prNumber обязательны"));
        }
        if (request.dryRun()) {
            ReviewResult result = reviews.preview(request.repository(), request.prNumber(), null);
            return ResponseEntity.ok(Map.of("dryRun", true, "review", result));
        }
        CodeReviewJob job = reviews.enqueueManual(request.repository(), request.prNumber(),
                request.force());
        return ResponseEntity.accepted().body(summary(job));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable long id) {
        CodeReviewJob job = reviews.retry(id);
        return ResponseEntity.accepted().body(summary(job));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.codeReview().enabled());
        status.put("giteaConfigured", gitea.configured());
        status.put("giteaBaseUrl", gitea.baseUrl());
        status.put("webhookSecretConfigured", properties.gitea().webhookConfigured());
        status.put("webhookUrl", "/api/code-review/webhook/gitea");
        status.put("allowedRepositories", properties.gitea().allowedRepositories());
        status.put("publish", properties.codeReview().publish());
        status.put("pollingEnabled", properties.codeReview().pollingEnabled());
        status.put("totalJobs", reviews.history(1000).size());
        return status;
    }

    static Map<String, Object> summary(CodeReviewJob job) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", job.id());
        view.put("repository", job.repository());
        view.put("prNumber", job.prNumber());
        view.put("headSha", job.headSha());
        view.put("status", job.status());
        view.put("triggeredBy", job.triggeredBy());
        view.put("provider", job.provider());
        view.put("model", job.model());
        view.put("fallback", job.fallback());
        view.put("cloud", job.cloud());
        view.put("partial", job.partial());
        view.put("summary", job.summary());
        view.put("overallRisk", job.overallRisk());
        view.put("error", job.error());
        view.put("findingsCount", job.findingsCount());
        view.put("inputTokens", job.inputTokens());
        view.put("outputTokens", job.outputTokens());
        view.put("estimatedCost", job.estimatedCost());
        view.put("costCurrency", job.costCurrency());
        view.put("durationMs", job.durationMs());
        view.put("publishedCommentId", job.publishedCommentId());
        view.put("createdAt", job.createdAt());
        List<String> skipped = new ArrayList<>();
        if (job.skippedFiles() != null && !job.skippedFiles().isBlank()) {
            skipped.addAll(List.of(job.skippedFiles().split("\\s*,\\s*")));
        }
        view.put("skippedFiles", skipped);
        return view;
    }

    /** Manual review request (repository must be in the allow-list). */
    public record ManualReviewRequest(String repository, Long prNumber, boolean force, boolean dryRun) {
    }
}
