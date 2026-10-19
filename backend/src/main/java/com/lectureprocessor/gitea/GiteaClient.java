package com.lectureprocessor.gitea;

import java.net.URI;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.lectureprocessor.config.AppProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read/write Gitea REST API client used by the AI code review pipeline.
 *
 * <p>Security properties:</p>
 * <ul>
 *   <li>the API token is sent only in the {@code Authorization: token ...}
 *       header, never in a URL or query string, and never logged;</li>
 *   <li>repositories are restricted to an explicit allow-list, so the backend
 *       cannot be used as a proxy to arbitrary Gitea repositories;</li>
 *   <li>plain HTTP is allowed only when explicitly enabled (homelab LAN).</li>
 * </ul>
 */
@Service
public class GiteaClient {

    private static final Logger log = LoggerFactory.getLogger(GiteaClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BLOB_BYTES = 400_000;
    private static final int MAX_TREE_ENTRIES = 5_000;

    private final AppProperties properties;
    private final RestClient client;
    private final String baseUrl;

    @Autowired
    public GiteaClient(AppProperties properties) {
        this(properties, defaultBuilder(properties));
    }

    GiteaClient(AppProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        AppProperties.Gitea gitea = properties.gitea();
        this.baseUrl = normalizeBase(gitea);
        this.client = builder.baseUrl(baseUrl).build();
    }

    static RestClient.Builder defaultBuilder(AppProperties properties) {
        AppProperties.Gitea gitea = properties.gitea();
        Duration timeout = gitea.timeout() == null ? Duration.ofSeconds(30) : gitea.timeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory);
    }

    public boolean configured() {
        return properties.gitea().configured();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** True when the repository is in the configured allow-list. */
    public boolean isAllowed(String repository) {
        List<String> allowed = properties.gitea().allowedRepositories();
        if (allowed == null || allowed.isEmpty() || repository == null) {
            return false;
        }
        String normalized = repository.strip().toLowerCase(Locale.ROOT);
        return allowed.stream().anyMatch(entry -> normalized.equals(entry.strip().toLowerCase(Locale.ROOT)));
    }

    public GiteaPullRequest getPullRequest(String repository, long number) {
        String[] parts = split(repository);
        JsonNode root = getJson("/api/v1/repos/{owner}/{repo}/pulls/{index}", parts[0], parts[1],
                String.valueOf(number));
        return new GiteaPullRequest(
                root.path("number").asLong(number),
                text(root, "title"),
                text(root, "state"),
                root.path("merged").asBoolean(false),
                text(root, "body"),
                root.path("base").path("ref").asString(""),
                root.path("base").path("sha").asString(""),
                root.path("head").path("ref").asString(""),
                root.path("head").path("sha").asString(""),
                text(root, "html_url"),
                root.path("user").path("login").asString(""));
    }

    public List<GiteaChangedFile> getChangedFiles(String repository, long number) {
        String[] parts = split(repository);
        JsonNode array = getJson("/api/v1/repos/{owner}/{repo}/pulls/{index}/files?limit=300",
                parts[0], parts[1], String.valueOf(number));
        List<GiteaChangedFile> files = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode node : array) {
                files.add(new GiteaChangedFile(
                        text(node, "filename"),
                        text(node, "previous_filename"),
                        text(node, "status"),
                        node.path("additions").asInt(0),
                        node.path("deletions").asInt(0),
                        node.path("changes").asInt(0)));
            }
        }
        return files;
    }

    /** Open pull requests for the pull-based polling fallback. */
    public List<GiteaPullRequest> listOpenPullRequests(String repository) {
        String[] parts = split(repository);
        JsonNode array = getJson("/api/v1/repos/{owner}/{repo}/pulls?state=open&limit=50", parts[0], parts[1]);
        List<GiteaPullRequest> pulls = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode node : array) {
                pulls.add(new GiteaPullRequest(
                        node.path("number").asLong(),
                        text(node, "title"),
                        text(node, "state"),
                        node.path("merged").asBoolean(false),
                        text(node, "body"),
                        node.path("base").path("ref").asString(""),
                        node.path("base").path("sha").asString(""),
                        node.path("head").path("ref").asString(""),
                        node.path("head").path("sha").asString(""),
                        text(node, "html_url"),
                        node.path("user").path("login").asString("")));
            }
        }
        return pulls;
    }

    /** Raw unified diff of the pull request. */
    public String getDiff(String repository, long number) {
        String[] parts = split(repository);
        String path = "/api/v1/repos/" + parts[0] + "/" + parts[1] + "/pulls/" + number + ".diff";
        try {
            String body = withAuth(client.get().uri(path)).accept(MediaType.TEXT_PLAIN)
                    .retrieve().body(String.class);
            return body == null ? "" : body;
        } catch (RestClientResponseException e) {
            throw classify(e);
        } catch (RuntimeException e) {
            throw transport(e);
        }
    }

    public List<GiteaComment> getIssueComments(String repository, long number) {
        String[] parts = split(repository);
        JsonNode array = getJson("/api/v1/repos/{owner}/{repo}/issues/{index}/comments?limit=100",
                parts[0], parts[1], String.valueOf(number));
        List<GiteaComment> comments = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode node : array) {
                comments.add(new GiteaComment(
                        node.path("id").asLong(),
                        text(node, "body"),
                        node.path("user").path("login").asString(""),
                        text(node, "created_at")));
            }
        }
        return comments;
    }

    public GiteaComment createIssueComment(String repository, long number, String body) {
        String[] parts = split(repository);
        try {
            String response = client.post()
                    .uri("/api/v1/repos/{owner}/{repo}/issues/{index}/comments", parts[0], parts[1], number)
                    .header("Authorization", "token " + requireToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsString(java.util.Map.of("body", body)))
                    .retrieve().body(String.class);
            JsonNode node = response == null ? JSON.createObjectNode() : JSON.readTree(response);
            return new GiteaComment(node.path("id").asLong(), body,
                    node.path("user").path("login").asString(""), text(node, "created_at"));
        } catch (RestClientResponseException e) {
            throw classify(e);
        } catch (RuntimeException e) {
            throw transport(e);
        }
    }

    /** File content at a specific ref, or {@code null} when missing/too large/binary. */
    public String getFileContent(String repository, String path, String ref) {
        String[] parts = split(repository);
        try {
            String encodedPath = org.springframework.web.util.UriUtils.encodePath(path, StandardCharsets.UTF_8);
            String encodedRef = java.net.URLEncoder.encode(ref == null ? "" : ref, StandardCharsets.UTF_8);
            URI uri = URI.create(baseUrl + "/api/v1/repos/" + parts[0] + "/" + parts[1]
                    + "/contents/" + encodedPath + "?ref=" + encodedRef);
            String response = withAuth(client.get().uri(uri)).retrieve().body(String.class);
            if (response == null || response.isBlank()) {
                return null;
            }
            JsonNode node = JSON.readTree(response);
            String encoding = text(node, "encoding");
            String content = text(node, "content");
            long size = node.path("size").asLong(0);
            if (content == null || (encoding != null && !"base64".equalsIgnoreCase(encoding))) {
                return null;
            }
            if (size > MAX_BLOB_BYTES) {
                log.debug("Skipping large file {} ({} bytes)", path, size);
                return null;
            }
            byte[] decoded = Base64.getMimeDecoder().decode(content);
            if (decoded.length > MAX_BLOB_BYTES || looksBinary(decoded)) {
                return null;
            }
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw classify(e);
        } catch (RuntimeException e) {
            throw transport(e);
        }
    }

    /** Recursive tree listing at a ref (blobs only). */
    public List<GiteaTreeEntry> listTree(String repository, String ref) {
        String[] parts = split(repository);
        JsonNode root = getJson("/api/v1/repos/{owner}/{repo}/git/trees/{ref}?recursive=true&per_page=1000",
                parts[0], parts[1], ref);
        List<GiteaTreeEntry> entries = new ArrayList<>();
        JsonNode tree = root.path("tree");
        if (tree.isArray()) {
            for (JsonNode node : tree) {
                if (entries.size() >= MAX_TREE_ENTRIES) {
                    break;
                }
                if (!"blob".equals(node.path("type").asString(""))) {
                    continue;
                }
                entries.add(new GiteaTreeEntry(
                        text(node, "path"),
                        "blob",
                        text(node, "sha"),
                        node.has("size") ? node.path("size").asLong(-1) : -1));
            }
        }
        return entries;
    }

    /** Blob content by Git object sha, or {@code null} when missing/too large/binary. */
    public String getBlob(String repository, String sha) {
        String[] parts = split(repository);
        JsonNode node = getJson("/api/v1/repos/{owner}/{repo}/git/blobs/{sha}", parts[0], parts[1], sha);
        String content = text(node, "content");
        long size = node.path("size").asLong(0);
        if (content == null || size > MAX_BLOB_BYTES) {
            return null;
        }
        byte[] decoded = Base64.getMimeDecoder().decode(content);
        if (decoded.length > MAX_BLOB_BYTES || looksBinary(decoded)) {
            return null;
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private JsonNode getJson(String path, Object... vars) {
        try {
            String response = withAuth(client.get().uri(path, vars)).retrieve().body(String.class);
            if (response == null || response.isBlank()) {
                throw new GiteaApiException(GiteaApiException.Category.INVALID_RESPONSE,
                        "Gitea вернул пустой ответ", 502);
            }
            return JSON.readTree(response);
        } catch (RestClientResponseException e) {
            throw classify(e);
        } catch (GiteaApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw transport(e);
        }
    }

    private RestClient.RequestHeadersSpec<?> withAuth(RestClient.RequestHeadersSpec<?> spec) {
        return spec.header("Authorization", "token " + requireToken());
    }

    private String requireToken() {
        String token = properties.gitea().token();
        if (token == null || token.isBlank()) {
            throw new GiteaApiException(GiteaApiException.Category.CONFIGURATION,
                    "Gitea token не настроен (GITEA_TOKEN)", 0);
        }
        return token;
    }

    private static GiteaApiException classify(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        return switch (status) {
            case 401 -> new GiteaApiException(GiteaApiException.Category.AUTHENTICATION,
                    "Gitea отклонил токен (401)", status);
            case 403 -> new GiteaApiException(GiteaApiException.Category.FORBIDDEN,
                    "Gitea запретил доступ (403): проверьте права токена", status);
            case 404 -> new GiteaApiException(GiteaApiException.Category.NOT_FOUND,
                    "Gitea: ресурс не найден (404)", status);
            case 429 -> new GiteaApiException(GiteaApiException.Category.RATE_LIMIT,
                    "Gitea ограничил частоту запросов (429)", status);
            default -> status >= 500
                    ? new GiteaApiException(GiteaApiException.Category.UNAVAILABLE,
                            "Gitea временно недоступен (" + status + ")", status)
                    : new GiteaApiException(GiteaApiException.Category.INVALID_RESPONSE,
                            "Gitea вернул ошибку " + status, status);
        };
    }

    private static GiteaApiException transport(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("timed out") || message.contains("timeout")) {
            return new GiteaApiException(GiteaApiException.Category.UNAVAILABLE,
                    "Gitea не ответил за отведённое время", 504, e);
        }
        return new GiteaApiException(GiteaApiException.Category.UNAVAILABLE,
                "Не удалось обратиться к Gitea", 502, e);
    }

    private static String normalizeBase(AppProperties.Gitea gitea) {
        String base = gitea.baseUrl() == null || gitea.baseUrl().isBlank()
                ? "https://gitea.example.com" : gitea.baseUrl().strip();
        URI uri = URI.create(base);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new GiteaApiException(GiteaApiException.Category.CONFIGURATION,
                    "Gitea base URL должен быть http(s)", 0);
        }
        if ("http".equals(scheme) && !gitea.allowHttp()) {
            throw new GiteaApiException(GiteaApiException.Category.CONFIGURATION,
                    "Gitea HTTP запрещён (app.gitea.allow-http=false)", 0);
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private String[] split(String repository) {
        if (repository == null || !repository.contains("/")) {
            throw new GiteaApiException(GiteaApiException.Category.CONFIGURATION,
                    "Некорректный repository (ожидается owner/repo)", 0);
        }
        if (!isAllowed(repository)) {
            throw new GiteaApiException(GiteaApiException.Category.FORBIDDEN,
                    "Репозиторий не входит в allow-list: " + repository, 403);
        }
        String[] parts = repository.split("/", 2);
        return new String[] { parts[0], parts[1] };
    }

    private static boolean looksBinary(byte[] data) {
        int sample = Math.min(data.length, 8000);
        for (int i = 0; i < sample; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.isString() ? value.asString() : null;
    }
}
