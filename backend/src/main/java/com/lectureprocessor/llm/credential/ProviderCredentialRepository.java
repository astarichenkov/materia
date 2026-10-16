package com.lectureprocessor.llm.credential;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for encrypted provider credentials. Only ciphertext (and a
 * non-secret hint) is ever stored; the plaintext key never reaches this layer.
 */
@Repository
public class ProviderCredentialRepository {

    private final JdbcTemplate jdbc;

    public ProviderCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProviderCredential> find(String providerId) {
        return jdbc.query("""
                SELECT provider_id, ciphertext, key_hint, encryption, updated_at, last_success_at, last_error
                FROM provider_credential WHERE provider_id = ?
                """, (rs, rowNum) -> new ProviderCredential(
                        rs.getString("provider_id"),
                        rs.getString("ciphertext"),
                        rs.getString("key_hint"),
                        rs.getString("encryption"),
                        parseInstant(rs.getString("updated_at")),
                        parseInstant(rs.getString("last_success_at")),
                        rs.getString("last_error")), providerId).stream().findFirst();
    }

    public void upsert(ProviderCredential credential) {
        jdbc.update("""
                INSERT INTO provider_credential
                        (provider_id, ciphertext, key_hint, encryption, updated_at, last_success_at, last_error)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(provider_id) DO UPDATE SET
                        ciphertext = excluded.ciphertext,
                        key_hint = excluded.key_hint,
                        encryption = excluded.encryption,
                        updated_at = excluded.updated_at,
                        last_success_at = excluded.last_success_at,
                        last_error = excluded.last_error
                """,
                credential.providerId(), credential.ciphertext(), credential.keyHint(), credential.encryption(),
                text(credential.updatedAt()), text(credential.lastSuccessAt()), credential.lastError());
    }

    public void updateStatus(String providerId, Instant lastSuccessAt, String lastError) {
        jdbc.update("""
                UPDATE provider_credential SET last_success_at = ?, last_error = ? WHERE provider_id = ?
                """, text(lastSuccessAt), lastError, providerId);
    }

    public int delete(String providerId) {
        return jdbc.update("DELETE FROM provider_credential WHERE provider_id = ?", providerId);
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return value.contains("T") ? Instant.parse(value) : new Timestamp(Long.parseLong(value)).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
