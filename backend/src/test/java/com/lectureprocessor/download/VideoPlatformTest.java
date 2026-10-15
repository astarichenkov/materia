package com.lectureprocessor.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.support.TestProperties;

class VideoPlatformTest {

    @Test
    void detectsSupportedPlatforms() {
        assertThat(VideoPlatform.fromUrl("https://www.youtube.com/watch?v=abc")).contains(VideoPlatform.YOUTUBE);
        assertThat(VideoPlatform.fromUrl("https://youtu.be/abc")).contains(VideoPlatform.YOUTUBE);
        assertThat(VideoPlatform.fromUrl("https://vkvideo.ru/video-123_456")).contains(VideoPlatform.VK_VIDEO);
        assertThat(VideoPlatform.fromUrl("https://vk.com/video-123_456")).contains(VideoPlatform.VK);
        assertThat(VideoPlatform.fromUrl("https://rutube.ru/video/xyz/")).contains(VideoPlatform.RUTUBE);
    }

    @Test
    void rejectsUnknownHosts() {
        assertThat(VideoPlatform.fromUrl("https://example.com/video")).isEmpty();
        assertThat(VideoPlatform.isSupported("not a url")).isFalse();
        assertThat(VideoPlatform.isSupported(null)).isFalse();
    }

    @Test
    void matchesSubdomainsButNotLookalikes() {
        assertThat(VideoPlatform.YOUTUBE.matchesHost("m.youtube.com")).isTrue();
        assertThat(VideoPlatform.YOUTUBE.matchesHost("notyoutube.com")).isFalse();
        assertThat(VideoPlatform.YOUTUBE.matchesHost("evil-youtube.com")).isFalse();
    }

    @Test
    void validatorRejectsUnsafeUrls() {
        UrlSafetyValidator validator = new UrlSafetyValidator(
                TestProperties.defaults("./target/test-data"));

        assertThatThrownBy(() -> validator.validate("http://youtube.com/watch?v=a"))
                .isInstanceOf(UnsafeMediaUrlException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> validator.validate("https://example.com/video"))
                .isInstanceOf(UnsafeMediaUrlException.class)
                .hasMessageContaining("Неподдерживаемая");
        assertThatThrownBy(() -> validator.validate("https://user:pass@youtube.com/video"))
                .isInstanceOf(UnsafeMediaUrlException.class)
                .hasMessageContaining("учётные данные");
        assertThatThrownBy(() -> validator.validate("ftp://youtube.com/video"))
                .isInstanceOf(UnsafeMediaUrlException.class);
    }

    @Test
    void forbiddenAddressesIncludePrivateAndLoopback() throws Exception {
        assertThat(UrlSafetyValidator.isForbidden(java.net.InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(UrlSafetyValidator.isForbidden(java.net.InetAddress.getByName("10.0.0.5"))).isTrue();
        assertThat(UrlSafetyValidator.isForbidden(java.net.InetAddress.getByName("192.168.1.10"))).isTrue();
        assertThat(UrlSafetyValidator.isForbidden(java.net.InetAddress.getByName("169.254.1.1"))).isTrue();
        assertThat(UrlSafetyValidator.isForbidden(java.net.InetAddress.getByName("100.64.0.1"))).isTrue();
        assertThat(UrlSafetyValidator.isForbidden(java.net.InetAddress.getByName("8.8.8.8"))).isFalse();
    }
}
