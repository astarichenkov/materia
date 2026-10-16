package com.lectureprocessor.codereview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.lectureprocessor.assistant.DocumentationRetrievalService;
import com.lectureprocessor.codereview.code.CodeIndexService;
import com.lectureprocessor.codereview.code.CodeRetrievalService;
import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.gitea.GiteaChangedFile;
import com.lectureprocessor.gitea.GiteaClient;
import com.lectureprocessor.gitea.GiteaComment;
import com.lectureprocessor.gitea.GiteaPullRequest;
import com.lectureprocessor.gitea.GiteaTreeEntry;
import com.lectureprocessor.llm.CloudCostService;
import com.lectureprocessor.repository.CodeChunkRepository;
import com.lectureprocessor.repository.DocChunkRepository;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.FixedTaskResolver;
import com.lectureprocessor.support.TestProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end (no Spring context, real SQLite) test of the review pipeline with a
 * mocked Gitea API and a deterministic fake LLM. No paid calls are made.
 */
class CodeReviewServiceTest {

    private static final String REPO = "example-org/materia";
    private static final String HEAD = "head222";
    private static final String DIFF = """
            diff --git a/backend/src/main/java/com/example/Service.java b/backend/src/main/java/com/example/Service.java
            index 111..222 100644
            --- a/backend/src/main/java/com/example/Service.java
            +++ b/backend/src/main/java/com/example/Service.java
            @@ -1,3 +1,4 @@
             public class Service {
                 public String find(String id) {
            +        return id.strip();
                 }
            """;
    private static final String REVIEW_JSON = """
            {"summary":"One null-safety issue found","overallRisk":"HIGH","findings":[
              {"category":"BUG","severity":"HIGH",
               "file":"backend/src/main/java/com/example/Service.java","line":3,
               "title":"Potential NPE","description":"id may be null","suggestion":"guard null","confidence":0.9}],
             "recommendations":["add a unit test"]}
            """;

    @TempDir
    Path tempDir;

    private GiteaClient gitea;
    private CodeReviewService service;
    private CodeReviewJobRepository jobs;
    private CodeReviewFindingRepository findings;
    private FakeLlmProvider llm;
    private AppProperties properties;
    private com.zaxxer.hikari.HikariDataSource dataSource;

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static AppProperties props(String root) {
        AppProperties base = TestProperties.defaults(root);
        AppProperties.CodeReview review = new AppProperties.CodeReview(true, 120000, 40, 24000, 15, 3, 50,
                200, List.of("backend/src"), List.of("**/.env"), 1800, 5, 0.0, true, Duration.ofSeconds(30),
                false, 60, true);
        return new AppProperties(base.tools(), base.storage(), base.upload(), base.processing(),
                base.ollama(), base.slides(), base.chat(), base.llm(), base.downloader(), base.assistant(),
                base.mcp(), base.deepseek(), base.security(), base.gitea(), review);
    }

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = sqlite(tempDir.resolve("review.db"));
        this.dataSource = (com.zaxxer.hikari.HikariDataSource) dataSource;
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
        }
        properties = props(tempDir.toString());
        jobs = new CodeReviewJobRepository(jdbc);
        findings = new CodeReviewFindingRepository(jdbc);
        CodeChunkRepository codeChunks = new CodeChunkRepository(jdbc);
        DocChunkRepository docChunks = new DocChunkRepository(jdbc);

        gitea = mock(GiteaClient.class);
        when(gitea.isAllowed(REPO)).thenReturn(true);
        when(gitea.configured()).thenReturn(true);
        when(gitea.getPullRequest(REPO, 7)).thenReturn(pr(HEAD));
        when(gitea.getChangedFiles(REPO, 7)).thenReturn(List.of(new GiteaChangedFile(
                "backend/src/main/java/com/example/Service.java", null, "modified", 1, 0, 1)));
        when(gitea.getDiff(REPO, 7)).thenReturn(DIFF);
        when(gitea.getFileContent(anyString(), anyString(), anyString()))
                .thenReturn("public class Service {\n  public String find(String id) {\n"
                        + "    return id.strip();\n  }\n}\n");
        when(gitea.getIssueComments(anyString(), anyLong())).thenReturn(List.of());
        when(gitea.createIssueComment(anyString(), anyLong(), anyString()))
                .thenReturn(new GiteaComment(999, "body", "bot", "now"));
        when(gitea.listTree(anyString(), anyString())).thenReturn(List.of(
                new GiteaTreeEntry("backend/src/main/java/com/example/Service.java", "blob", "blob1", 100L),
                new GiteaTreeEntry("backend/src/main/java/com/example/Helper.java", "blob", "blob2", 80L)));
        when(gitea.getBlob(anyString(), anyString())).thenReturn("public class Helper {\n  int value() { return 1; }\n}\n");

        llm = new FakeLlmProvider();
        llm.setId("ollama");
        llm.setDetailed(true);
        llm.setTokenUsage(11, 22);
        llm.setChatFunction((system, user) -> REVIEW_JSON);
        FixedTaskResolver resolver = new FixedTaskResolver(llm);

        CodeIndexService codeIndex = new CodeIndexService(gitea, llm, codeChunks, properties);
        CodeRetrievalService codeRetrieval = new CodeRetrievalService(codeChunks, llm);
        DocumentationRetrievalService docRetrieval = new DocumentationRetrievalService(docChunks, llm,
                properties);
        CodeReviewContextBuilder contextBuilder = new CodeReviewContextBuilder(gitea, codeIndex, codeRetrieval,
                docRetrieval, properties);
        CodeReviewAnalyzer analyzer = new CodeReviewAnalyzer(resolver, new ReviewPromptBuilder(),
                new ReviewResultParser(), new ReviewValidator(), new CloudCostService(properties), properties);
        GiteaReviewPublisher publisher = new GiteaReviewPublisher(gitea, properties);

        service = new CodeReviewService(gitea, contextBuilder, analyzer, publisher, jobs, findings,
                properties, new ObjectMapper(), task -> { });
    }

    private static GiteaPullRequest pr(String head) {
        return new GiteaPullRequest(7, "Fix find", "open", false, "body", "main", "base111", "feature", head,
                "http://gitea/pulls/7", "anton");
    }

    private static DataSource sqlite(Path path) {
        com.zaxxer.hikari.HikariDataSource dataSource = new com.zaxxer.hikari.HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:" + path.toAbsolutePath());
        dataSource.setMaximumPoolSize(1);
        dataSource.setPoolName("code-review-test");
        return dataSource;
    }

    @Test
    void runsFullReviewPublishesAndPersistsFindings() {
        CodeReviewJob queued = service.enqueue(REPO, 7, HEAD, "manual", false);
        service.execute(queued.id());

        CodeReviewJob stored = jobs.findById(queued.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(stored.provider()).isEqualTo("ollama");
        assertThat(stored.findingsCount()).isEqualTo(1);
        assertThat(stored.inputTokens()).isPositive();
        assertThat(stored.publishedCommentId()).isEqualTo("999");
        assertThat(stored.durationMs()).isNotNull();
        assertThat(findings.findByJob(queued.id())).hasSize(1);
        assertThat(findings.findByJob(queued.id()).getFirst().line()).isEqualTo(3);
        verify(gitea).createIssueComment(anyString(), anyLong(), anyString());
    }

    @Test
    void duplicateEnqueueReturnsSameJob() {
        CodeReviewJob first = service.enqueue(REPO, 7, HEAD, "manual", false);
        CodeReviewJob second = service.enqueue(REPO, 7, HEAD, "webhook", false);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jobs.countAll()).isEqualTo(1);
    }

    @Test
    void forceEnqueueCreatesNewJob() {
        CodeReviewJob first = service.enqueue(REPO, 7, HEAD, "manual", false);
        CodeReviewJob forced = service.enqueue(REPO, 7, HEAD, "manual", true);
        assertThat(forced.id()).isNotEqualTo(first.id());
        assertThat(jobs.countAll()).isEqualTo(2);
    }

    @Test
    void failedLlmMarksJobFailedWithoutPublishing() {
        llm.setChatFunction((system, user) -> {
            throw new RuntimeException("model unavailable");
        });
        CodeReviewJob queued = service.enqueue(REPO, 7, HEAD, "manual", false);
        service.execute(queued.id());

        CodeReviewJob stored = jobs.findById(queued.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(ReviewStatus.FAILED);
        assertThat(stored.error()).isNotBlank();
        verify(gitea, never()).createIssueComment(anyString(), anyLong(), anyString());
    }

    @Test
    void malformedJsonIsRepairedWithinBoundedCalls() {
        int[] calls = { 0 };
        llm.setChatFunction((system, user) -> {
            calls[0]++;
            return calls[0] == 1 ? "not json at all" : REVIEW_JSON;
        });
        CodeReviewJob queued = service.enqueue(REPO, 7, HEAD, "manual", false);
        service.execute(queued.id());

        CodeReviewJob stored = jobs.findById(queued.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(stored.llmCalls()).isEqualTo(2);
        assertThat(stored.findingsCount()).isEqualTo(1);
    }

    @Test
    void staleHeadIsSupersededAndNotPublished() {
        CodeReviewJob queued = service.enqueue(REPO, 7, HEAD, "manual", false);
        // The PR head moves before publishing.
        when(gitea.getPullRequest(REPO, 7)).thenReturn(pr("newhead333"));
        service.execute(queued.id());

        CodeReviewJob stored = jobs.findById(queued.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(ReviewStatus.SUPERSEDED);
        verify(gitea, never()).createIssueComment(anyString(), anyLong(), anyString());
    }

    @Test
    void idempotentKeyIncludesConfigVersionAndSha() {
        String key = CodeReviewService.idempotencyKey(REPO, 7, HEAD, "v1");
        assertThat(key).isEqualTo(REPO + "#7@head222#v1");
    }

    @Test
    void recordsQueueAndStageTimings() {
        CodeReviewJob queued = service.enqueue(REPO, 7, HEAD, "manual", false);
        service.execute(queued.id());
        CodeReviewJob stored = jobs.findById(queued.id()).orElseThrow();
        assertThat(stored.giteaMs()).isNotNull();
        assertThat(stored.retrievalMs()).isNotNull();
        assertThat(stored.llmMs()).isNotNull();
        assertThat(stored.llmCalls()).isEqualTo(1);
    }

    @Test
    void rejectsRepositoryOutsideAllowList() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.enqueue("other/repo", 1, "sha", "manual", false))
                .isInstanceOf(com.lectureprocessor.gitea.GiteaApiException.class);
    }
}
