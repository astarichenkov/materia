package com.lectureprocessor.codereview.web;

import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.codereview.CodeReviewJob;
import com.lectureprocessor.codereview.CodeReviewService;
import com.lectureprocessor.gitea.GiteaApiException;
import com.lectureprocessor.gitea.GiteaClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates and processes Gitea pull-request webhooks. The HTTP handler only
 * validates and enqueues; the actual review runs on the bounded review executor.
 */
@Service
public class CodeReviewWebhookService {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewWebhookService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "opened", "reopened", "synchronize", "synchronized", "edited", "ready_for_review");

    private final GiteaWebhookVerifier verifier;
    private final WebhookGuards guards;
    private final GiteaClient gitea;
    private final CodeReviewService reviews;

    public CodeReviewWebhookService(GiteaWebhookVerifier verifier, WebhookGuards guards, GiteaClient gitea,
            CodeReviewService reviews) {
        this.verifier = verifier;
        this.guards = guards;
        this.gitea = gitea;
        this.reviews = reviews;
    }

    public record Outcome(int status, String message, Long jobId, String repository, Long prNumber,
            String headSha) {
    }

    public Outcome handle(String event, String signature, String delivery, byte[] body) {
        if (!verifier.secretConfigured()) {
            return new Outcome(503, "Gitea webhook secret не настроен (GITEA_WEBHOOK_SECRET)", null, null, null,
                    null);
        }
        if (!verifier.verify(body, signature)) {
            log.warn("Rejected Gitea webhook with invalid signature (event={})", event);
            return new Outcome(401, "Неверная подпись webhook", null, null, null, null);
        }
        if (event == null || !"pull_request".equalsIgnoreCase(event)) {
            return new Outcome(202, "Событие проигнорировано: " + event, null, null, null, null);
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(body);
        } catch (RuntimeException e) {
            return new Outcome(400, "Некорректный JSON webhook", null, null, null, null);
        }
        String action = text(payload, "action");
        if (action == null || !SUPPORTED_ACTIONS.contains(action.toLowerCase(Locale.ROOT))) {
            return new Outcome(202, "Действие проигнорировано: " + action, null, null, null, null);
        }
        String repository = payload.path("repository").path("full_name").asString("");
        long prNumber = payload.path("pull_request").path("number").asLong(
                payload.path("number").asLong(0));
        String headSha = payload.path("pull_request").path("head").path("sha").asString("");
        if (repository.isBlank() || prNumber <= 0 || headSha.isBlank()) {
            return new Outcome(400, "Webhook не содержит repository/number/head SHA", null, null, null, null);
        }
        if (!gitea.isAllowed(repository)) {
            return new Outcome(403, "Репозиторий не входит в allow-list: " + repository, null, repository,
                    prNumber, headSha);
        }
        if (!guards.allow(repository)) {
            return new Outcome(429, "Слишком много webhook-событий для " + repository, null, repository,
                    prNumber, headSha);
        }
        if (guards.isReplay(delivery)) {
            return new Outcome(200, "Повторная доставка проигнорирована", null, repository, prNumber, headSha);
        }
        try {
            CodeReviewJob job = reviews.enqueue(repository, prNumber, headSha, "webhook", false);
            return new Outcome(202, "Review поставлен в очередь", job.id(), repository, prNumber, headSha);
        } catch (GiteaApiException e) {
            int status = e.category() == GiteaApiException.Category.CONFIGURATION ? 503 : 502;
            return new Outcome(status, e.getMessage(), null, repository, prNumber, headSha);
        } catch (RuntimeException e) {
            return new Outcome(500, "Не удалось поставить review в очередь: " + e.getMessage(), null,
                    repository, prNumber, headSha);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }
}
