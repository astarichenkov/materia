package com.lectureprocessor.codereview;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.gitea.GiteaClient;
import com.lectureprocessor.gitea.GiteaComment;
import com.lectureprocessor.gitea.GiteaPullRequest;

/**
 * Publishes a validated review as a single Markdown PR comment.
 *
 * <p>Safety rules:</p>
 * <ul>
 *   <li>idempotent per {@code (repository, PR, head SHA)} using an HTML marker in
 *       the comment body — re-delivered webhooks never duplicate a review;</li>
 *   <li>stale suppression: the PR head is re-fetched before publishing, so a
 *       review of an outdated SHA is never posted as the current one;</li>
 *   <li>only new comments are created; human comments are never edited or
 *       deleted; no approval/merge is performed.</li>
 * </ul>
 */
@Service
public class GiteaReviewPublisher {

    private static final Logger log = LoggerFactory.getLogger(GiteaReviewPublisher.class);
    private static final String MARKER_PREFIX = "<!-- ai-code-review:";
    private static final String BOT_SIGNATURE = "_Automated review. Human verification recommended._";

    private final GiteaClient gitea;
    private final AppProperties properties;

    public GiteaReviewPublisher(GiteaClient gitea, AppProperties properties) {
        this.gitea = gitea;
        this.properties = properties;
    }

    public PublishedReview publish(CodeReviewJob job, ReviewResult result, boolean force) {
        if (!properties.codeReview().publish()) {
            return new PublishedReview(false, false, false, null, 0, "Публикация отключена (dry-run)");
        }
        long start = System.nanoTime();
        // 1) Stale check: only publish if the PR head still equals the reviewed SHA.
        GiteaPullRequest current = gitea.getPullRequest(job.repository(), job.prNumber());
        if (current.headSha() == null || !current.headSha().equalsIgnoreCase(job.headSha())) {
            return PublishedReview.superseded("PR head изменился: " + shortSha(current.headSha())
                    + " != " + shortSha(job.headSha()));
        }
        // 2) Duplicate suppression for the same head SHA (skipped for explicit force re-review).
        String marker = marker(job.repository(), job.prNumber(), job.headSha());
        if (!force) {
            List<GiteaComment> comments = gitea.getIssueComments(job.repository(), job.prNumber());
            for (GiteaComment comment : comments) {
                if (comment.body() != null && comment.body().contains(marker)) {
                    return PublishedReview.duplicate(String.valueOf(comment.id()));
                }
            }
        }
        // 3) Publish.
        String body = buildMarkdown(job, result, marker, current.htmlUrl());
        GiteaComment created = gitea.createIssueComment(job.repository(), job.prNumber(), body);
        long publishMs = (System.nanoTime() - start) / 1_000_000;
        log.info("Published AI review for {}#{} @{} as comment {}", job.repository(), job.prNumber(),
                shortSha(job.headSha()), created.id());
        return new PublishedReview(true, false, false, String.valueOf(created.id()), publishMs, null);
    }

    public String marker(String repository, long prNumber, String headSha) {
        return MARKER_PREFIX + repository + "#" + prNumber + "@" + shortSha(headSha) + " -->";
    }

    String buildMarkdown(CodeReviewJob job, ReviewResult result, String marker, String prUrl) {
        StringBuilder md = new StringBuilder();
        md.append("## AI Code Review\n\n");
        md.append("Model: `").append(escape(result.provider())).append(" / ")
                .append(escape(result.model())).append("`\n\n");
        md.append("Commit: `").append(escape(shortSha(job.headSha()))).append("` · Risk: **")
                .append(escape(result.overallRisk())).append("**\n\n");
        if (result.fallback()) {
            md.append("> **Warning:** primary provider was unavailable; this review used a fallback "
                    + "provider and may be less accurate.\n\n");
        }
        if (result.partial()) {
            md.append("> **Partial review:** diff/context was truncated. Files skipped: ")
                    .append(escape(join(result.skippedFiles()))).append("\n\n");
        }
        md.append("### Summary\n\n").append(escape(result.summary())).append("\n\n");

        appendFindings(md, "Critical / High priority", result.findings(), ReviewSeverity.CRITICAL,
                ReviewSeverity.HIGH);
        appendFindings(md, "Medium priority", result.findings(), ReviewSeverity.MEDIUM);
        appendFindings(md, "Low priority / suggestions", result.findings(), ReviewSeverity.LOW);

        if (result.findings().isEmpty()) {
            md.append("No confirmed problems were found in the changed code. ")
                    .append("This is not a guarantee that the change is correct.\n\n");
        }
        if (!result.recommendations().isEmpty()) {
            md.append("### Recommendations\n\n");
            for (String recommendation : result.recommendations()) {
                md.append("- ").append(escape(recommendation)).append('\n');
            }
            md.append('\n');
        }
        if (prUrl != null && !prUrl.isBlank()) {
            md.append("[Open pull request](").append(prUrl).append(")\n\n");
        }
        md.append("---\n\n").append(BOT_SIGNATURE).append('\n');
        md.append(marker).append('\n');
        return md.toString();
    }

    private static void appendFindings(StringBuilder md, String title, List<ReviewFinding> findings,
            ReviewSeverity... severities) {
        List<ReviewSeverity> accepted = List.of(severities);
        List<ReviewFinding> selected = findings.stream().filter(f -> accepted.contains(f.severity())).toList();
        if (selected.isEmpty()) {
            return;
        }
        md.append("### ").append(title).append("\n\n");
        int index = 1;
        for (ReviewFinding finding : selected) {
            md.append(index++).append(". **").append(escape(finding.title())).append("** — `")
                    .append(escape(finding.category().name().toLowerCase(Locale.ROOT))).append("`\n");
            md.append("   - Location: `").append(escape(finding.file()));
            if (finding.line() != null) {
                md.append(':').append(finding.line());
            }
            md.append("` (confidence ").append(finding.confidence() == null ? "n/a"
                    : String.format(Locale.ROOT, "%.2f", finding.confidence())).append(")\n");
            md.append("   - ").append(escape(finding.description())).append('\n');
            if (finding.suggestion() != null) {
                md.append("   - Suggestion: ").append(escape(finding.suggestion())).append('\n');
            }
            md.append('\n');
        }
    }

    /** Escapes HTML control characters so untrusted text cannot inject markup. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(", ", values);
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(8, sha.length()));
    }
}
