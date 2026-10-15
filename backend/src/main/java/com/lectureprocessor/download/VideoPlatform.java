package com.lectureprocessor.download;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Supported video platforms. The allow-list is intentionally narrow: only the
 * official public hostnames are accepted. yt-dlp may still contact platform CDN
 * hosts internally (that is unavoidable), but the user-supplied URL can only
 * point at one of these hosts.
 */
public enum VideoPlatform {

    YOUTUBE("YouTube", List.of("youtube.com", "youtu.be")),
    VK_VIDEO("VK Видео", List.of("vkvideo.ru")),
    VK("VK", List.of("vk.com")),
    RUTUBE("Rutube", List.of("rutube.ru"));

    private final String displayName;
    private final List<String> allowedHosts;

    VideoPlatform(String displayName, List<String> allowedHosts) {
        this.displayName = displayName;
        this.allowedHosts = allowedHosts;
    }

    public String displayName() {
        return displayName;
    }

    public List<String> allowedHosts() {
        return allowedHosts;
    }

    public boolean matchesHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return allowedHosts.stream()
                .anyMatch(allowed -> normalized.equals(allowed) || normalized.endsWith("." + allowed));
    }

    public static Optional<VideoPlatform> fromUrl(String url) {
        String host = hostOf(url);
        if (host == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(p -> p.matchesHost(host)).findFirst();
    }

    public static boolean isSupported(String url) {
        return fromUrl(url).isPresent();
    }

    public static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            return uri.getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
