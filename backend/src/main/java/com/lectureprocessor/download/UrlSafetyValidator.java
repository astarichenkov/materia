package com.lectureprocessor.download;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;

/**
 * SSRF / scheme / DNS guard for user-supplied media URLs.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>supported platform host allow-list (incl. subdomains);</li>
 *   <li>HTTPS-only unless explicitly allowed in configuration;</li>
 *   <li>no user-info in the URL (would leak credentials into process args/logs);</li>
 *   <li>every resolved address must be public (no loopback / private / link-local / multicast);</li>
 *   <li>bounded URL length.</li>
 * </ul>
 *
 * <p>Limitation: yt-dlp itself follows CDN redirects internally; this guard
 * protects the initial URL and its DNS, not each subsequent CDN connection.</p>
 */
@Component
public class UrlSafetyValidator {

    private static final Logger log = LoggerFactory.getLogger(UrlSafetyValidator.class);
    private static final int MAX_URL_LENGTH = 2048;

    private final AppProperties properties;

    public UrlSafetyValidator(AppProperties properties) {
        this.properties = properties;
    }

    public VideoPlatform validate(String url) {
        if (url == null || url.isBlank()) {
            throw new UnsafeMediaUrlException("URL не задан");
        }
        String trimmed = url.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new UnsafeMediaUrlException("Слишком длинный URL");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new UnsafeMediaUrlException("Некорректный URL: " + e.getMessage());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        boolean allowHttp = properties.downloader().allowHttp();
        if (!"https".equals(scheme) && !(allowHttp && "http".equals(scheme))) {
            throw new UnsafeMediaUrlException("Разрешён только HTTPS (получено: " + scheme + ")");
        }
        if (uri.getUserInfo() != null) {
            throw new UnsafeMediaUrlException("URL не должен содержать учётные данные");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new UnsafeMediaUrlException("Не удалось определить хост URL");
        }
        VideoPlatform platform = VideoPlatform.fromUrl(trimmed)
                .orElseThrow(() -> new UnsafeMediaUrlException(
                        "Неподдерживаемая платформа: " + host + ". Поддерживаются YouTube, VK Видео, VK, Rutube."));
        assertPublicHost(host);
        return platform;
    }

    /**
     * Validates a non-media resource URL (for example a thumbnail advertised by
     * the extractor metadata). Unlike {@link #validate(String)} it does not
     * require a supported video platform, but it still enforces the scheme and
     * public-host policy, so a compromised metadata payload cannot turn into an
     * SSRF request against the local network.
     *
     * @return the parsed URI of a safe resource
     */
    public URI validateResourceUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new UnsafeMediaUrlException("URL ресурса не задан");
        }
        String trimmed = url.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new UnsafeMediaUrlException("Слишком длинный URL ресурса");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new UnsafeMediaUrlException("Некорректный URL ресурса: " + e.getMessage());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        boolean allowHttp = properties.downloader().allowHttp();
        if (!"https".equals(scheme) && !(allowHttp && "http".equals(scheme))) {
            throw new UnsafeMediaUrlException("Разрешён только HTTPS для ресурса (получено: "
                    + scheme + ")");
        }
        if (uri.getUserInfo() != null) {
            throw new UnsafeMediaUrlException("URL ресурса не должен содержать учётные данные");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new UnsafeMediaUrlException("Не удалось определить хост ресурса");
        }
        assertPublicHost(host);
        return uri;
    }

    /** Rejects hosts that resolve to non-public addresses (SSRF protection). */
    public void assertPublicHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new UnsafeMediaUrlException("Не удалось разрешить хост: " + host);
        }
        if (addresses.length == 0) {
            throw new UnsafeMediaUrlException("Хост не разрешается в адрес: " + host);
        }
        for (InetAddress address : addresses) {
            if (isForbidden(address)) {
                log.warn("Rejected media URL host {} resolving to {}", host, address.getHostAddress());
                throw new UnsafeMediaUrlException(
                        "Хост разрешается во внутренний/приватный адрес: " + host);
            }
        }
    }

    static boolean isForbidden(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address)
                || isCarrierGradeNat(address);
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4
                && (bytes[0] & 0xFF) == 100
                && (bytes[1] & 0xC0) == 0x40;
    }
}
