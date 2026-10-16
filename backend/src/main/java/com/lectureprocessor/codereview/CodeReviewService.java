package com.lectureprocessor.codereview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.codereview.diff.DiffFile;
import com.lectureprocessor.codereview.diff.UnifiedDiffParser;
import com.lectureprocessor.gitea.GiteaApiException;
import com.lectureprocessor.gitea.GiteaChangedFile;
import com.lectureprocessor.gitea.GiteaClient;
import com.lectureprocessor.gitea.GiteaPullRequest;

import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end AI code review orchestrator: Gitea fetch → commit-consistent
 * context → LLM analysis → validation → publishing, with persistence,
 * idempotency, stale-run suppression and per-stage metrics.
 */
@Service
public class CodeReviewService {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewService.class);

    private final GiteaClient gitea;
    private final CodeReviewContextBuilder contextBuilder;
    private final CodeReviewAnalyzer analyzer;
    private final GiteaReviewPublisher publisher;
    private final CodeReviewJobRepository jobs;
    private final CodeReviewFindingRepository findings;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;

    public CodeReviewService(GiteaClient gitea, CodeReviewContextBuilder contextBuilder,
            CodeReviewAnalyzer analyzer, GiteaReviewPublisher publisher, CodeReviewJobRepository jobs,
            CodeReviewFindingRepository findings, AppProperties properties, ObjectMapper objectMapper,
            @Qualifier("codeReviewExecutor") TaskExecutor executor) {
        this.gitea = gitea;
        this.contextBuilder = contextBuilder;
        this.analyzer = analyzer;
        this.publisher = publisher;
        this.jobs = jobs;
        this.findings = findings;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /** Enqueues a review unless an identical, non-forced run already exists. */
    public CodeReviewJob enqueue(String repository, long prNumber, String headSha, String triggeredBy,
            boolean force) {
        requireEnabled();
        if (!gitea.configured()) {
            throw new GiteaApiException(GiteaApiException.Category.CONFIGURATION,
                    "Gitea token не настроен (GITEA_TOKEN)", 0);
        }
        if (!gitea.isAllowed(repository)) {
            throw new GiteaApiException(GiteaApiException.Category.FORBIDDEN,
                    "Репозиторий не входит в allow-list: " + repository, 403);
        }
        String configVersion = configVersion();
        String key = idempotencyKey(repository, prNumber, headSha, configVersion);
        if (!force) {
            var existing = jobs.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return existing.get();
            }
        } else {
            key = key + "#force-" + UUID.randomUUID();
        }
        String effectiveTrigger = force && "manual".equals(triggeredBy) ? "manual-force" : triggeredBy;
        CodeReviewJob job = new CodeReviewJob(null, repository, prNumber, headSha, null, configVersion, key,
                ReviewStatus.QUEUED, effectiveTrigger, null, null, false, false, false, null, null, null, null,
                0, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                Instant.now(), null, null);
        CodeReviewJob saved = jobs.save(job);
        jobs.supersedeRunning(repository, prNumber, key, "Заменён новым review для более свежего commit");
        submit(saved.id());
        return saved;
    }

    /** Re-runs an existing job (explicit retry with fresh authorisation checks). */
    public CodeReviewJob retry(long jobId) {
        CodeReviewJob job = jobs.findById(jobId).orElseThrow(
                () -> new IllegalArgumentException("Review job не найден: " + jobId));
        return enqueue(job.repository(), job.prNumber(), job.headSha(), "retry", true);
    }

    /** Manual review: resolves the current PR head and enqueues it. */
    public CodeReviewJob enqueueManual(String repository, long prNumber, boolean force) {
        requireEnabled();
        if (!gitea.isAllowed(repository)) {
            throw new GiteaApiException(GiteaApiException.Category.FORBIDDEN,
                    "Репозиторий не входит в allow-list: " + repository, 403);
        }
        GiteaPullRequest pr = gitea.getPullRequest(repository, prNumber);
        return enqueue(repository, prNumber, pr.headSha(), "manual", force);
    }

    private void submit(long jobId) {
        try {
            executor.execute(() -> execute(jobId));
        } catch (RuntimeException e) {
            jobs.findById(jobId).ifPresent(job -> jobs.save(job.withFailure("Очередь переполнена")));
            throw new IllegalStateException("Не удалось поставить review в очередь", e);
        }
    }

    /** Runs one review synchronously (also used directly by tests). */
    public void execute(long jobId) {
        CodeReviewJob job = jobs.findById(jobId).orElseThrow(
                () -> new IllegalArgumentException("Review job не найден: " + jobId));
        long start = System.currentTimeMillis();
        long queueMs = job.createdAt() == null ? 0 : start - job.createdAt().toEpochMilli();
        job = jobs.save(job.withStatus(ReviewStatus.FETCHING_DIFF));
        boolean force = job.triggeredBy() != null && job.triggeredBy().startsWith("manual-force");

        try {
            // 1) Fetch PR, changed files and diff.
            long giteaStart = System.currentTimeMillis();
            GiteaPullRequest pr = gitea.getPullRequest(job.repository(), job.prNumber());
            if (pr.headSha() == null || pr.headSha().isBlank()) {
                throw new GiteaApiException(GiteaApiException.Category.INVALID_RESPONSE,
                        "Gitea не вернул head SHA", 502);
            }
            List<GiteaChangedFile> changed = gitea.getChangedFiles(job.repository(), job.prNumber());
            String diff = gitea.getDiff(job.repository(), job.prNumber());
            long giteaMs = System.currentTimeMillis() - giteaStart;

            List<DiffFile> parsed = UnifiedDiffParser.parse(diff);
            boolean diffTruncated = diff.length() > properties.codeReview().maxDiffChars();
            ReviewDiffInfo diffInfo = new ReviewDiffInfo(parsed,
                    CodeReviewContextBuilder.extractIdentifiers(diff), diffTruncated);

            // 2) Commit-consistent context (changed files + code RAG + docs RAG).
            job = jobs.save(job.withStatus(ReviewStatus.RETRIEVING_CONTEXT));
            long retrievalStart = System.currentTimeMillis();
            ReviewContext context = contextBuilder.build(job.repository(), job.prNumber(), pr, changed, diff,
                    diffInfo);
            long retrievalMs = System.currentTimeMillis() - retrievalStart;

            // 3) Analyze.
            job = jobs.save(job.withStatus(ReviewStatus.ANALYZING));
            ReviewResult result = analyzer.analyze(context, diffInfo);

            // 4) Validate/persist findings.
            job = jobs.save(job.withStatus(ReviewStatus.VALIDATING));
            findings.replaceForJob(job.id(), result.findings());

            job = job.withTimings(giteaMs, retrievalMs, result.llmMs(), result.llmCalls(), changed.size(),
                    context.analyzedFiles().size(), String.join(", ", context.skippedFiles()));

            if (result.failed()) {
                jobs.save(job.withFailure(result.error()));
                log.warn("Review {}#{} failed: {}", job.repository(), job.prNumber(), result.error());
                return;
            }

            // 5) Publish.
            job = job.withResult(ReviewStatus.PUBLISHING, result.provider(), result.model(), result.fallback(),
                    result.cloud(), result.partial(), result.summary(), result.overallRisk(),
                    result.findings().size(), result.inputTokens(), result.outputTokens(), null, null,
                    String.join(", ", context.skippedFiles()), context.analyzedFiles().size());
            jobs.save(job);

            PublishedReview published = publisher.publish(job, result, force);
            long durationMs = System.currentTimeMillis() - start;
            if (published.superseded()) {
                jobs.save(job.withResult(ReviewStatus.SUPERSEDED, result.provider(), result.model(),
                        result.fallback(), result.cloud(), result.partial(), result.summary(),
                        result.overallRisk(), result.findings().size(), result.inputTokens(),
                        result.outputTokens(), null, null, String.join(", ", context.skippedFiles()),
                        context.analyzedFiles().size()).withDuration(durationMs));
                log.info("Review {}#{}@{} superseded: {}", job.repository(), job.prNumber(),
                        shortSha(job.headSha()), published.note());
                return;
            }
            job = job.withPublished(published.commentId(), published.publishMs(), durationMs);
            job = job.withTimings(giteaMs, retrievalMs, result.llmMs(), result.llmCalls(), changed.size(),
                    context.analyzedFiles().size(), String.join(", ", context.skippedFiles()));
            jobs.save(job);
            log.info("Review {}#{}@{} completed (published={} duplicate={} duration={}ms)",
                    job.repository(), job.prNumber(), shortSha(job.headSha()), published.published(),
                    published.duplicate(), durationMs);
        } catch (GiteaApiException e) {
            jobs.save(job.withFailure("Gitea: " + e.getMessage()));
            log.warn("Review {}#{} Gitea error: {}", job.repository(), job.prNumber(), e.getMessage());
        } catch (RuntimeException e) {
            jobs.save(job.withFailure("Ошибка review: " + e.getMessage()));
            log.error("Review {}#{} failed unexpectedly", job.repository(), job.prNumber(), e);
        }
    }

    /** Builds the context and runs analysis without publishing (dry-run/preview). */
    public ReviewResult preview(String repository, long prNumber, String headSha) {
        requireEnabled();
        GiteaPullRequest pr = gitea.getPullRequest(repository, prNumber);
        List<GiteaChangedFile> changed = gitea.getChangedFiles(repository, prNumber);
        String diff = gitea.getDiff(repository, prNumber);
        List<DiffFile> parsed = UnifiedDiffParser.parse(diff);
        ReviewDiffInfo diffInfo = new ReviewDiffInfo(parsed, CodeReviewContextBuilder.extractIdentifiers(diff),
                diff.length() > properties.codeReview().maxDiffChars());
        ReviewContext context = contextBuilder.build(repository, prNumber, pr, changed, diff, diffInfo);
        return analyzer.analyze(context, diffInfo);
    }

    public List<CodeReviewJob> history(int limit) {
        return jobs.findAll(limit);
    }

    public CodeReviewJob get(long id) {
        return jobs.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Review job не найден: " + id));
    }

    public List<ReviewFinding> findings(long jobId) {
        return findings.findByJob(jobId);
    }

    private void requireEnabled() {
        if (!properties.codeReview().enabled()) {
            throw new IllegalStateException("Code review отключён (app.code-review.enabled=false)");
        }
    }

    private String configVersion() {
        try {
            String json = objectMapper.writeValueAsString(properties.codeReview());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((ReviewPromptBuilder.PROMPT_VERSION + "|" + json)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return ReviewPromptBuilder.PROMPT_VERSION;
        }
    }

    static String idempotencyKey(String repository, long prNumber, String headSha, String configVersion) {
        return repository + "#" + prNumber + "@" + headSha + "#" + configVersion;
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(8, sha.length()));
    }
}
