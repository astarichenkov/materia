package com.lectureprocessor.codereview;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.gitea.GiteaApiException;
import com.lectureprocessor.gitea.GiteaClient;
import com.lectureprocessor.gitea.GiteaPullRequest;

/**
 * Pull-based fallback trigger.
 *
 * <p>If Gitea cannot reach the Windows host over the LAN (firewall, no inbound
 * port), this poller detects new pull-request head SHAs and enqueues a review
 * exactly as a webhook would. It is idempotent: a head SHA that already has any
 * review job is skipped, so polling never duplicates work.</p>
 */
@Component
public class GiteaPullRequestPoller {

    private static final Logger log = LoggerFactory.getLogger(GiteaPullRequestPoller.class);

    private final GiteaClient gitea;
    private final CodeReviewService reviews;
    private final CodeReviewJobRepository jobs;
    private final AppProperties properties;

    public GiteaPullRequestPoller(GiteaClient gitea, CodeReviewService reviews, CodeReviewJobRepository jobs,
            AppProperties properties) {
        this.gitea = gitea;
        this.reviews = reviews;
        this.jobs = jobs;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "#{${app.code-review.poll-interval-seconds:60} * 1000}",
            initialDelayString = "#{${app.code-review.poll-interval-seconds:60} * 1000}")
    public void poll() {
        AppProperties.CodeReview config = properties.codeReview();
        if (!config.enabled() || !config.pollingEnabled() || !gitea.configured()) {
            return;
        }
        List<String> repositories = properties.gitea().allowedRepositories();
        if (repositories == null || repositories.isEmpty()) {
            return;
        }
        for (String repository : repositories) {
            if (repository == null || repository.isBlank() || !gitea.isAllowed(repository)) {
                continue;
            }
            try {
                List<GiteaPullRequest> pulls = gitea.listOpenPullRequests(repository);
                for (GiteaPullRequest pull : pulls) {
                    if (pull.headSha() == null || pull.headSha().isBlank()) {
                        continue;
                    }
                    boolean alreadyReviewed = jobs.findLatestForSha(repository, pull.number(), pull.headSha())
                            .isPresent();
                    if (!alreadyReviewed) {
                        CodeReviewJob job = reviews.enqueue(repository, pull.number(), pull.headSha(),
                                "polling", false);
                        log.info("Polling detected PR {}#{} @{} -> review job {}", repository, pull.number(),
                                shortSha(pull.headSha()), job.id());
                    }
                }
            } catch (GiteaApiException e) {
                log.debug("Polling Gitea for {} failed: {}", repository, e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Polling for {} failed: {}", repository, e.getMessage());
            }
        }
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(8, sha.length()));
    }
}
