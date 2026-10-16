package com.lectureprocessor.codereview;

import java.time.Instant;

/**
 * Persisted code-review job. Every measured value that is unknown stays
 * {@code null} rather than being fabricated.
 *
 * @param id                 surrogate key (null until inserted)
 * @param repository         {@code owner/repo}
 * @param prNumber           pull request number
 * @param headSha            head commit under review (idempotency axis)
 * @param baseSha            base commit
 * @param configVersion      hash of the code-review config + prompt version
 * @param idempotencyKey     repository#pr#headSha#configVersion (unique)
 * @param status             see {@link ReviewStatus}
 * @param triggeredBy        webhook / manual / polling / retry
 * @param provider           provider actually used
 * @param model              model actually used
 * @param fallback           true when a fallback provider was used
 * @param cloud              true for a cloud provider
 * @param partial            true when the diff/context was truncated
 * @param summary            model summary
 * @param overallRisk        LOW/MEDIUM/HIGH/CRITICAL
 * @param error              failure reason (secret-free)
 * @param publishedCommentId Gitea comment id once published
 * @param findingsCount      number of published findings
 * @param inputTokens        real prompt tokens (null when unknown)
 * @param outputTokens       real completion tokens (null when unknown)
 * @param estimatedCost      estimated cloud cost (null when not priced)
 * @param costCurrency       cost currency
 * @param durationMs         total wall time
 * @param queueMs            time from enqueue to start
 * @param giteaMs            Gitea API time
 * @param retrievalMs        RAG/context time
 * @param llmMs              LLM time
 * @param publishMs          publishing time
 * @param llmCalls           number of model calls
 * @param changedFiles       changed files in the PR
 * @param analyzedFiles      files actually analysed
 * @param skippedFiles       comma-separated skipped files (truncation visibility)
 */
public record CodeReviewJob(
        Long id,
        String repository,
        long prNumber,
        String headSha,
        String baseSha,
        String configVersion,
        String idempotencyKey,
        String status,
        String triggeredBy,
        String provider,
        String model,
        boolean fallback,
        boolean cloud,
        boolean partial,
        String summary,
        String overallRisk,
        String error,
        String publishedCommentId,
        int findingsCount,
        Integer inputTokens,
        Integer outputTokens,
        Double estimatedCost,
        String costCurrency,
        Long durationMs,
        Long queueMs,
        Long giteaMs,
        Long retrievalMs,
        Long llmMs,
        Long publishMs,
        Integer llmCalls,
        Integer changedFiles,
        Integer analyzedFiles,
        String skippedFiles,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public CodeReviewJob withId(long newId) {
        return copy(newId, status, provider, model, fallback, cloud, partial, summary, overallRisk, error,
                publishedCommentId, findingsCount, inputTokens, outputTokens, estimatedCost, costCurrency,
                durationMs, queueMs, giteaMs, retrievalMs, llmMs, publishMs, llmCalls, changedFiles,
                analyzedFiles, skippedFiles, createdAt, startedAt, finishedAt);
    }

    public CodeReviewJob withStatus(String newStatus) {
        return copy(id, newStatus, provider, model, fallback, cloud, partial, summary, overallRisk, error,
                publishedCommentId, findingsCount, inputTokens, outputTokens, estimatedCost, costCurrency,
                durationMs, queueMs, giteaMs, retrievalMs, llmMs, publishMs, llmCalls, changedFiles,
                analyzedFiles, skippedFiles, createdAt, startedAt == null ? Instant.now() : startedAt, finishedAt);
    }

    public CodeReviewJob withTimings(Long newGiteaMs, Long newRetrievalMs, Long newLlmMs, Integer newLlmCalls,
            Integer newChangedFiles, Integer newAnalyzedFiles, String newSkippedFiles) {
        return copy(id, status, provider, model, fallback, cloud, partial, summary, overallRisk, error,
                publishedCommentId, findingsCount, inputTokens, outputTokens, estimatedCost, costCurrency,
                durationMs, queueMs, newGiteaMs == null ? giteaMs : newGiteaMs,
                newRetrievalMs == null ? retrievalMs : newRetrievalMs,
                newLlmMs == null ? llmMs : newLlmMs,
                null,
                newLlmCalls == null ? llmCalls : newLlmCalls,
                newChangedFiles == null ? changedFiles : newChangedFiles,
                newAnalyzedFiles == null ? analyzedFiles : newAnalyzedFiles,
                newSkippedFiles == null ? skippedFiles : newSkippedFiles,
                createdAt, startedAt, finishedAt);
    }

    public CodeReviewJob withResult(String newStatus, String newProvider, String newModel, boolean newFallback,
            boolean newCloud, boolean newPartial, String newSummary, String newOverallRisk,
            int newFindingsCount, Integer newInputTokens, Integer newOutputTokens, Double newEstimatedCost,
            String newCurrency, String newSkippedFiles, Integer newAnalyzedFiles) {
        return copy(id, newStatus, newProvider, newModel, newFallback, newCloud, newPartial, newSummary,
                newOverallRisk, null, publishedCommentId, newFindingsCount, newInputTokens, newOutputTokens,
                newEstimatedCost, newCurrency, durationMs, queueMs, giteaMs, retrievalMs, llmMs, publishMs,
                llmCalls, changedFiles, newAnalyzedFiles == null ? analyzedFiles : newAnalyzedFiles,
                newSkippedFiles == null ? skippedFiles : newSkippedFiles, createdAt, startedAt, finishedAt);
    }

    public CodeReviewJob withFailure(String message) {
        return copy(id, ReviewStatus.FAILED, provider, model, fallback, cloud, partial, summary, overallRisk,
                message, publishedCommentId, findingsCount, inputTokens, outputTokens, estimatedCost,
                costCurrency, durationMs, queueMs, giteaMs, retrievalMs, llmMs, publishMs, llmCalls,
                changedFiles, analyzedFiles, skippedFiles, createdAt, startedAt, Instant.now());
    }

    public CodeReviewJob withPublished(String commentId, Long newPublishMs, Long newDurationMs) {
        return copy(id, ReviewStatus.COMPLETED, provider, model, fallback, cloud, partial, summary, overallRisk,
                null, commentId, findingsCount, inputTokens, outputTokens, estimatedCost, costCurrency,
                newDurationMs == null ? durationMs : newDurationMs, queueMs, giteaMs, retrievalMs, llmMs,
                newPublishMs, llmCalls, changedFiles, analyzedFiles, skippedFiles, createdAt, startedAt,
                Instant.now());
    }

    public CodeReviewJob withDuration(Long newDurationMs) {
        return copy(id, status, provider, model, fallback, cloud, partial, summary, overallRisk, error,
                publishedCommentId, findingsCount, inputTokens, outputTokens, estimatedCost, costCurrency,
                newDurationMs, queueMs, giteaMs, retrievalMs, llmMs, publishMs, llmCalls, changedFiles,
                analyzedFiles, skippedFiles, createdAt, startedAt, finishedAt == null ? Instant.now() : finishedAt);
    }

    private CodeReviewJob copy(Long newId, String newStatus, String newProvider, String newModel,
            boolean newFallback, boolean newCloud, boolean newPartial, String newSummary, String newRisk,
            String newError, String newCommentId, int newFindings, Integer newInput, Integer newOutput,
            Double newCost, String newCurrency, Long newDuration, Long newQueue, Long newGitea, Long newRetrieval,
            Long newLlm, Long newPublish, Integer newCalls, Integer newChanged, Integer newAnalyzed,
            String newSkipped, Instant newCreated, Instant newStarted, Instant newFinished) {
        return new CodeReviewJob(newId, repository, prNumber, headSha, baseSha, configVersion, idempotencyKey,
                newStatus, triggeredBy, newProvider, newModel, newFallback, newCloud, newPartial, newSummary,
                newRisk, newError, newCommentId, newFindings, newInput, newOutput, newCost, newCurrency,
                newDuration, newQueue, newGitea, newRetrieval, newLlm, newPublish, newCalls, newChanged,
                newAnalyzed, newSkipped, newCreated, newStarted, newFinished);
    }
}
