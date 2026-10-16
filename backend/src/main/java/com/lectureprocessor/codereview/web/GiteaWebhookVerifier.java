package com.lectureprocessor.codereview.web;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;

/**
 * Verifies Gitea webhook authenticity with the shared HMAC-SHA256 secret
 * ({@code X-Gitea-Signature}). Unsigned or wrongly signed deliveries are
 * rejected; an IP allow-list is intentionally not relied upon.
 */
@Component
public class GiteaWebhookVerifier {

    private final AppProperties properties;

    public GiteaWebhookVerifier(AppProperties properties) {
        this.properties = properties;
    }

    public boolean secretConfigured() {
        return properties.gitea().webhookConfigured();
    }

    public boolean verify(byte[] body, String signatureHeader) {
        if (!secretConfigured()) {
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        String provided = signatureHeader.strip();
        if (provided.startsWith("sha256=")) {
            provided = provided.substring("sha256=".length());
        }
        String expected = hmacHex(body, properties.gitea().webhookSecret());
        return expected != null && constantTimeEquals(expected, provided.toLowerCase(Locale.ROOT));
    }

    static String hmacHex(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body == null ? new byte[0] : body);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
