package com.lectureprocessor.codereview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.gitea.GiteaClient;
import com.lectureprocessor.gitea.GiteaComment;
import com.lectureprocessor.gitea.GiteaPullRequest;
import com.lectureprocessor.support.TestProperties;

/** Publishing idempotency, stale suppression and markdown safety. */
class GiteaReviewPublisherTest {

    private GiteaClient gitea;
    private GiteaReviewPublisher publisher;

    private static AppProperties props(boolean publish) {
        AppProperties base = TestProperties.defaults("./target/test-data");
        AppProperties.CodeReview review = new AppProperties.CodeReview(true, 120000, 40, 24000, 15, 3, 150,
                1200, List.of("backend/src"), List.of("**/.env"), 1800, 8, 0.15, false, Duration.ofSeconds(30),
                false, 60, publish);
        return new AppProperties(base.tools(), base.storage(), base.upload(), base.processing(),
                base.ollama(), base.slides(), base.chat(), base.llm(), base.downloader(), base.assistant(),
                base.mcp(), base.deepseek(), base.security(), base.gitea(), review);
    }

    private static CodeReviewJob job() {
        return new CodeReviewJob(1L, "example-org/materia", 7, "head222", "base111", "cfg", "key",
                ReviewStatus.PUBLISHING, "webhook", "deepseek", "deepseek-flash", false, true, false, "sum",
                "HIGH", null, null, 1, 10, 20, 0.01, "USD", 100L, 0L, 0L, 0L, 0L, 0L, 1, 1, 1, null,
                Instant.now(), Instant.now(), null);
    }

    private static ReviewResult result() {
        return new ReviewResult("Summary text", List.of(new ReviewFinding(ReviewCategory.BUG,
                ReviewSeverity.HIGH, "src/A.java", 42, "Null deref", "possible NPE", "guard it", 0.9)),
                List.of("Add a test"), "HIGH", "deepseek", "deepseek-flash", false, true, false,
                List.of("src/A.java"), List.of(), 10, 20, 1, 50, null);
    }

    @BeforeEach
    void setUp() {
        gitea = mock(GiteaClient.class);
        publisher = new GiteaReviewPublisher(gitea, props(true));
    }

    @Test
    void publishesNewComment() {
        when(gitea.getPullRequest("example-org/materia", 7))
                .thenReturn(new GiteaPullRequest(7, "t", "open", false, "", "main", "base111", "f", "head222",
                        "http://gitea/pulls/7", "anton"));
        when(gitea.getIssueComments(anyString(), anyLong())).thenReturn(List.of());
        when(gitea.createIssueComment(anyString(), anyLong(), anyString()))
                .thenReturn(new GiteaComment(555, "body", "bot", "now"));

        PublishedReview published = publisher.publish(job(), result(), false);
        assertThat(published.published()).isTrue();
        assertThat(published.commentId()).isEqualTo("555");
    }

    @Test
    void suppressesDuplicateForSameSha() {
        when(gitea.getPullRequest("example-org/materia", 7))
                .thenReturn(new GiteaPullRequest(7, "t", "open", false, "", "main", "base111", "f", "head222",
                        "u", "anton"));
        when(gitea.getIssueComments(anyString(), anyLong())).thenReturn(List.of(
                new GiteaComment(99, "## AI Code Review\n" + publisher.marker(
                        "example-org/materia", 7, "head222"), "bot", "now")));

        PublishedReview published = publisher.publish(job(), result(), false);
        assertThat(published.duplicate()).isTrue();
        verify(gitea, never()).createIssueComment(anyString(), anyLong(), anyString());
    }

    @Test
    void forceBypassesDuplicateSuppression() {
        when(gitea.getPullRequest("example-org/materia", 7))
                .thenReturn(new GiteaPullRequest(7, "t", "open", false, "", "main", "base111", "f", "head222",
                        "u", "anton"));
        when(gitea.getIssueComments(anyString(), anyLong())).thenReturn(List.of());
        when(gitea.createIssueComment(anyString(), anyLong(), anyString()))
                .thenReturn(new GiteaComment(777, "body", "bot", "now"));

        PublishedReview published = publisher.publish(job(), result(), true);
        assertThat(published.published()).isTrue();
    }

    @Test
    void suppressesStaleReviewWhenHeadMoved() {
        when(gitea.getPullRequest("example-org/materia", 7))
                .thenReturn(new GiteaPullRequest(7, "t", "open", false, "", "main", "base111", "f", "newhead",
                        "u", "anton"));

        PublishedReview published = publisher.publish(job(), result(), false);
        assertThat(published.superseded()).isTrue();
        verify(gitea, never()).createIssueComment(anyString(), anyLong(), anyString());
    }

    @Test
    void markdownEscapesUntrustedHtml() {
        ReviewResult malicious = new ReviewResult("<script>alert(1)</script>",
                List.of(new ReviewFinding(ReviewCategory.SECURITY, ReviewSeverity.CRITICAL, "src/A.java", 1,
                        "<b>title</b>", "a < b && c > d", "<img src=x>", 0.9)),
                List.of("<script>"), "CRITICAL", "deepseek", "m", false, true, false, List.of("src/A.java"),
                List.of(), 1, 1, 1, 1, null);
        String markdown = publisher.buildMarkdown(job(), malicious,
                publisher.marker("example-org/materia", 7, "head222"), "u");
        assertThat(markdown).doesNotContain("<script>").doesNotContain("<img src=x>");
        assertThat(markdown).contains("&lt;script&gt;");
    }

    @Test
    void dryRunDoesNotPublish() {
        GiteaReviewPublisher dryRun = new GiteaReviewPublisher(gitea, props(false));
        PublishedReview published = dryRun.publish(job(), result(), false);
        assertThat(published.published()).isFalse();
        assertThat(published.note()).contains("dry-run");
        verify(gitea, never()).getPullRequest(anyString(), anyLong());
    }

    @Test
    void capturedBodyContainsModelAndMarker() {
        when(gitea.getPullRequest("example-org/materia", 7))
                .thenReturn(new GiteaPullRequest(7, "t", "open", false, "", "main", "base111", "f", "head222",
                        "http://gitea/pulls/7", "anton"));
        when(gitea.getIssueComments(anyString(), anyLong())).thenReturn(List.of());
        when(gitea.createIssueComment(anyString(), anyLong(), anyString()))
                .thenReturn(new GiteaComment(1, "b", "bot", "now"));

        publisher.publish(job(), result(), false);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(gitea).createIssueComment(anyString(), anyLong(), body.capture());
        assertThat(body.getValue()).contains("AI Code Review").contains("deepseek-flash")
                .contains("head222")
                .contains(publisher.marker("example-org/materia", 7, "head222"));
    }
}
