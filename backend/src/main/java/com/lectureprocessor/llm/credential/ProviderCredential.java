package com.lectureprocessor.llm.credential;

import java.time.Instant;

/**
 * Encrypted credential row. The plaintext key is never part of this record.
 *
 * @param providerId    provider the credential belongs to
 * @param ciphertext    versioned AES-256-GCM ciphertext
 * @param keyHint       non-secret hint (e.g. last 4 characters), may be null
 * @param encryption    format marker, e.g. {@code aes-256-gcm-v1}
 * @param updatedAt     last time the key was saved/replaced
 * @param lastSuccessAt last successful provider connection (null until tested)
 * @param lastError     last connection error (sanitized, never contains the key)
 */
public record ProviderCredential(
        String providerId,
        String ciphertext,
        String keyHint,
        String encryption,
        Instant updatedAt,
        Instant lastSuccessAt,
        String lastError) {
}
