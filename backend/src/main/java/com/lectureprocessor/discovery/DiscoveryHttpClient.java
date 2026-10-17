package com.lectureprocessor.discovery;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal hardened JSON HTTP client for discovery providers.
 *
 * <p>All URLs are built from configured provider bases (never from user input),
 * redirects are disabled (SSRF protection), plain HTTP is only permitted for
 * loopback test doubles, responses are size-capped and credentials never appear
 * in error messages.</p>
 */
@Component
public class DiscoveryHttpClient {

    private final DiscoveryProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public DiscoveryHttpClient(DiscoveryProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public JsonNode get(String url, Map<String, String> headers) {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !loopback(uri.getHost())) {
            throw new DiscoveryHttpException("Провайдер поиска требует HTTPS: " + uri.getHost());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout())
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (compatible; LectureProcessor/1.0; discovery)")
                .GET();
        headers.forEach(builder::header);
        try {
            HttpResponse<byte[]> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (body != null && body.length > properties.maxResponseBytes()) {
                throw new DiscoveryHttpException("Ответ провайдера слишком большой");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DiscoveryHttpException("Провайдер вернул HTTP " + response.statusCode());
            }
            return objectMapper.readTree(new String(body == null ? new byte[0] : body,
                    StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryHttpException("Запрос к провайдеру прерван", e);
        } catch (IOException | RuntimeException e) {
            if (e instanceof DiscoveryHttpException http) {
                throw http;
            }
            throw new DiscoveryHttpException("Не удалось получить ответ провайдера: "
                    + e.getClass().getSimpleName(), e);
        }
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static boolean loopback(String host) {
        if (host == null) {
            return false;
        }
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host);
    }

    /** Timeout used for yt-dlp search subprocesses as well. */
    public Duration timeout() {
        return properties.requestTimeout() == null ? Duration.ofSeconds(20)
                : properties.requestTimeout();
    }
}
