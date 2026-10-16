package com.lectureprocessor.llm.credential;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for provider credentials with a unique random nonce per
 * ciphertext and a versioned on-disk format.
 *
 * <p>Format: {@code v1:}<code>base64(nonce || ciphertext || tag)</code>. The
 * version prefix lets the storage format evolve without breaking existing
 * installations. No custom cryptography is implemented: only JCE AES/GCM.</p>
 */
@Component
public class CredentialCipher {

    static final String VERSION_PREFIX = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final MasterKeyStore keys;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(MasterKeyStore keys) {
        this.keys = keys;
    }

    /** Encrypts a non-empty credential into the versioned, base64 format. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new CredentialException("Пустое значение credential не может быть зашифровано");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.key(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array();
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            // Deliberately do not include plaintext or key material in the message.
            throw new CredentialException("Не удалось зашифровать credential", e);
        }
    }

    /** Decrypts a value produced by {@link #encrypt(String)}. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
            throw new CredentialException("Неизвестный формат credential (ожидается " + VERSION_PREFIX + ")");
        }
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new CredentialException("Повреждённое значение credential (base64)", e);
        }
        if (combined.length <= NONCE_BYTES) {
            throw new CredentialException("Повреждённое значение credential (слишком короткое)");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] sealed = new byte[combined.length - NONCE_BYTES];
        System.arraycopy(combined, 0, nonce, 0, NONCE_BYTES);
        System.arraycopy(combined, NONCE_BYTES, sealed, 0, sealed.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keys.key(), new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new CredentialException(
                    "Не удалось расшифровать credential: неверный master key или повреждённые данные", e);
        }
    }
}
