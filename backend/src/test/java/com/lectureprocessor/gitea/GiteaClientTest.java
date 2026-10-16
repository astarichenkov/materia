package com.lectureprocessor.gitea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.support.TestProperties;

/** Gitea REST client contract, with a mocked HTTP layer (no real Gitea calls). */
class GiteaClientTest {

    private static final String BASE = "http://gitea.test:8108";
    private MockRestServiceServer server;
    private GiteaClient client;

    private static AppProperties props(String token, String allowed) {
        AppProperties base = TestProperties.defaults("./target/test-data");
        return new AppProperties(base.tools(), base.storage(), base.upload(), base.processing(),
                base.ollama(), base.slides(), base.chat(), base.llm(), base.downloader(), base.assistant(),
                base.mcp(), base.deepseek(), base.security(),
                new AppProperties.Gitea(BASE, token, "secret", java.util.List.of(allowed),
                        Duration.ofSeconds(5), true),
                base.codeReview());
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GiteaClient(props("tok-123", "example-org/materia"), builder);
    }

    @Test
    void fetchesPullRequestWithAuthorizationHeader() {
        server.expect(requestTo(BASE + "/api/v1/repos/example-org/materia/pulls/7"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "token tok-123"))
                .andRespond(withSuccess("""
                        {"number":7,"title":"Fix bug","state":"open","merged":false,
                         "base":{"ref":"main","sha":"base111"},"head":{"ref":"feature","sha":"head222"},
                         "html_url":"http://gitea/pulls/7","user":{"login":"anton"}}
                        """, MediaType.APPLICATION_JSON));

        GiteaPullRequest pr = client.getPullRequest("example-org/materia", 7);
        assertThat(pr.number()).isEqualTo(7);
        assertThat(pr.headSha()).isEqualTo("head222");
        assertThat(pr.baseSha()).isEqualTo("base111");
    }

    @Test
    void fetchesChangedFiles() {
        server.expect(requestTo(BASE + "/api/v1/repos/example-org/materia/pulls/7/files?limit=300"))
                .andRespond(withSuccess("""
                        [{"filename":"src/A.java","status":"modified","additions":3,"deletions":1,"changes":4}]
                        """, MediaType.APPLICATION_JSON));
        var files = client.getChangedFiles("example-org/materia", 7);
        assertThat(files).hasSize(1);
        assertThat(files.getFirst().filename()).isEqualTo("src/A.java");
    }

    @Test
    void fetchesRawDiff() {
        server.expect(requestTo(BASE + "/api/v1/repos/example-org/materia/pulls/7.diff"))
                .andRespond(withSuccess("diff --git a/a b/a\n+line\n", MediaType.TEXT_PLAIN));
        assertThat(client.getDiff("example-org/materia", 7)).contains("+line");
    }

    @Test
    void decodesFileContent() {
        String content = Base64.getEncoder().encodeToString("class A {}".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(BASE + "/api/v1/repos/example-org/materia/contents/src/A.java?ref=head222"))
                .andRespond(withSuccess("{\"encoding\":\"base64\",\"content\":\"" + content
                        + "\",\"size\":10}", MediaType.APPLICATION_JSON));
        assertThat(client.getFileContent("example-org/materia", "src/A.java", "head222"))
                .isEqualTo("class A {}");
    }

    @Test
    void createsIssueComment() {
        server.expect(requestTo(BASE + "/api/v1/repos/example-org/materia/issues/7/comments"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":555,\"body\":\"hi\"}", MediaType.APPLICATION_JSON));
        GiteaComment comment = client.createIssueComment("example-org/materia", 7, "hi");
        assertThat(comment.id()).isEqualTo(555);
    }

    @Test
    void classifiesAuthenticationFailure() {
        server.expect(requestTo(BASE + "/api/v1/repos/example-org/materia/pulls/7"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.getPullRequest("example-org/materia", 7))
                .isInstanceOf(GiteaApiException.class)
                .extracting(e -> ((GiteaApiException) e).category())
                .isEqualTo(GiteaApiException.Category.AUTHENTICATION);
    }

    @Test
    void missingPullRequestIsNotFound() {
        server.expect(requestTo(BASE + "/api/v1/repos/example-org/materia/pulls/99"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.getPullRequest("example-org/materia", 99))
                .isInstanceOf(GiteaApiException.class)
                .extracting(e -> ((GiteaApiException) e).category())
                .isEqualTo(GiteaApiException.Category.NOT_FOUND);
    }

    @Test
    void rejectsRepositoriesOutsideAllowList() {
        assertThatThrownBy(() -> client.getPullRequest("other/repo", 1))
                .isInstanceOf(GiteaApiException.class)
                .extracting(e -> ((GiteaApiException) e).category())
                .isEqualTo(GiteaApiException.Category.FORBIDDEN);
    }

    @Test
    void missingTokenIsConfigurationError() {
        GiteaClient noToken = new GiteaClient(props("", "example-org/materia"), RestClient.builder());
        assertThatThrownBy(() -> noToken.getPullRequest("example-org/materia", 1))
                .isInstanceOf(GiteaApiException.class)
                .extracting(e -> ((GiteaApiException) e).category())
                .isEqualTo(GiteaApiException.Category.CONFIGURATION);
    }
}
