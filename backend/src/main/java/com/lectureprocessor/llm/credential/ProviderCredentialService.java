package com.lectureprocessor.llm.credential;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Secure lifecycle for per-installation provider credentials.
 *
 * <p>Responsibilities and guarantees:</p>
 * <ul>
 *   <li>encrypt at rest (AES-256-GCM, unique nonce, versioned format) via
 *       {@link CredentialCipher};</li>
 *   <li>never return the plaintext key through any API or log it;</li>
 *   <li>expose a non-secret status (configured, hint, last success/error);</li>
 *   <li>the master key lives outside the database ({@link MasterKeyStore}).</li>
 * </ul>
 *
 * <p>Each installation has its own key file / environment variable, so two
 * copies of the application never share credentials.</p>
 */
@Service
public class ProviderCredentialService {

    private static final int MIN_KEY_LENGTH = 8;
    private static final int MAX_KEY_LENGTH = 512;
    private static final int HINT_LENGTH = 4;

    private final ProviderCredentialRepository repository;
    private final CredentialCipher cipher;
    private final MasterKeyStore keys;

    public ProviderCredentialService(ProviderCredentialRepository repository, CredentialCipher cipher,
            MasterKeyStore keys) {
        this.repository = repository;
        this.cipher = cipher;
        this.keys = keys;
    }

    /** Saves (or replaces) the API key for a provider. */
    public CredentialStatus save(String providerId, String apiKey) {
        String normalized = normalize(providerId);
        String key = apiKey == null ? "" : apiKey.strip();
        if (key.isEmpty()) {
            throw new CredentialException("API key не может быть пустым");
        }
        if (key.length() < MIN_KEY_LENGTH || key.length() > MAX_KEY_LENGTH) {
            throw new CredentialException("API key имеет некорректную длину (" + MIN_KEY_LENGTH + "–"
                    + MAX_KEY_LENGTH + " символов)");
        }
        Optional<ProviderCredential> existing = repository.find(normalized);
        String ciphertext = cipher.encrypt(key);
        repository.upsert(new ProviderCredential(normalized, ciphertext, hint(key),
                "aes-256-gcm-v1", Instant.now(),
                existing.map(ProviderCredential::lastSuccessAt).orElse(null),
                null));
        return status(normalized);
    }

    /** Deletes the stored API key. Returns true when a credential existed. */
    public boolean delete(String providerId) {
        return repository.delete(normalize(providerId)) > 0;
    }

    /** True when a credential has been configured for the provider. */
    public boolean isConfigured(String providerId) {
        return repository.find(normalize(providerId)).isPresent();
    }

    /**
     * Decrypts the stored key for internal provider use. Never expose the result
     * through REST/HTML. Returns empty when nothing is configured.
     */
    public Optional<String> resolveKey(String providerId) {
        return repository.find(normalize(providerId)).map(credential -> cipher.decrypt(credential.ciphertext()));
    }

    /** Non-secret status safe to render or serialize. */
    public CredentialStatus status(String providerId) {
        return repository.find(normalize(providerId))
                .map(credential -> new CredentialStatus(
                        credential.providerId(),
                        true,
                        credential.keyHint(),
                        credential.updatedAt(),
                        credential.lastSuccessAt(),
                        sanitizeError(credential.lastError()),
                        !keys.usesExternalKey()))
                .orElseGet(() -> new CredentialStatus(normalize(providerId), false, null, null, null, null,
                        !keys.usesExternalKey()));
    }

    /** Records a successful connection test. */
    public void markSuccess(String providerId) {
        repository.updateStatus(normalize(providerId), Instant.now(), null);
    }

    /** Records a failed connection test with a sanitized message. */
    public void markError(String providerId, String error) {
        repository.updateStatus(normalize(providerId), null, sanitizeError(error));
    }

    /** True when the master key is only the fallback file (documented limitation). */
    public boolean usingFallbackMasterKey() {
        return !keys.usesExternalKey();
    }

    private static String normalize(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new CredentialException("Не задан provider id");
        }
        return providerId.strip().toLowerCase();
    }

    /** Non-secret hint: the last few characters, computed at save time only. */
    private static String hint(String key) {
        return key.length() <= HINT_LENGTH ? "****" : "…" + key.substring(key.length() - HINT_LENGTH);
    }

    /** Truncates and strips any accidental newlines; never includes the key. */
    private static String sanitizeError(String error) {
        if (error == null || error.isBlank()) {
            return null;
        }
        String cleaned = error.replaceAll("\\s+", " ").strip();
        return cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
    }

    /** Non-secret credential status exposed to the UI/API. */
    public record CredentialStatus(
            String providerId,
            boolean configured,
            String keyHint,
            Instant updatedAt,
            Instant lastSuccessAt,
            String lastError,
            boolean fallbackMasterKey) {
    }
}
