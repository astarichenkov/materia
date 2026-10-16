package com.lectureprocessor.llm.credential;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;

/**
 * Supplies the per-installation AES-256-GCM master key used to encrypt provider
 * credentials at rest.
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li>the configured environment variable (preferred — the key lives in the
 *       OS/secret-manager layer, outside the project tree);</li>
 *   <li>a key file with restricted permissions under the storage root, created
 *       on first use. This is a documented fallback: the key file must live
 *       outside backup/source-control of the database file.</li>
 * </ol>
 *
 * <p>The key is never logged and never returned through any API.</p>
 */
@Component
public class MasterKeyStore {

    private static final Logger log = LoggerFactory.getLogger(MasterKeyStore.class);
    private static final int KEY_BYTES = 32;
    private static final String KEY_FORMAT = "raw-base64";

    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKey cached;
    private volatile boolean external;

    public MasterKeyStore(AppProperties properties) {
        this.properties = properties;
    }

    /** Returns the master key, creating the fallback key file when necessary. */
    public synchronized SecretKey key() {
        if (cached != null) {
            return cached;
        }
        AppProperties.Security security = properties.security();
        String envName = security == null || security.masterKeyEnv() == null || security.masterKeyEnv().isBlank()
                ? "LP_MASTER_KEY" : security.masterKeyEnv();
        String envValue = System.getenv(envName);
        if (envValue == null || envValue.isBlank()) {
            envValue = System.getProperty(envName);
        }
        if (envValue != null && !envValue.isBlank()) {
            cached = decode(envValue, "environment variable " + envName);
            external = true;
            log.info("Provider credential master key loaded from environment ({})", envName);
            return cached;
        }

        Path path = masterKeyPath();
        try {
            if (Files.exists(path)) {
                String stored = Files.readString(path, StandardCharsets.US_ASCII).strip();
                cached = decode(stored, "key file");
                external = false;
                log.info("Provider credential master key loaded from {}", path);
                return cached;
            }
            byte[] raw = new byte[KEY_BYTES];
            random.nextBytes(raw);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, Base64.getEncoder().encodeToString(raw), StandardCharsets.US_ASCII);
            restrictPermissions(path);
            cached = new SecretKeySpec(raw, "AES");
            external = false;
            log.warn("Generated a new provider credential master key at {}. Keep this file outside backups "
                    + "of the database and never commit it. Prefer setting {} for production installs.",
                    path, envName);
            return cached;
        } catch (IOException e) {
            throw new CredentialException(
                    "Не удалось получить master key для шифрования credentials (файл " + path + "). "
                            + "Задайте переменную окружения " + envName + " с 32-байтовым base64-ключом.", e);
        }
    }

    /** True when the key comes from an external secret store rather than the fallback file. */
    public boolean usesExternalKey() {
        key();
        return external;
    }

    private Path masterKeyPath() {
        AppProperties.Security security = properties.security();
        String configured = security == null ? null : security.masterKeyPath();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(properties.storage().root(), ".secrets", "master.key");
    }

    private static SecretKey decode(String value, String source) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw new CredentialException("Некорректный master key (" + source + "): ожидается base64", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new CredentialException("Некорректный master key (" + source + "): нужно "
                    + KEY_BYTES + " байт, получено " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }

    private static void restrictPermissions(Path path) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> ownerOnly = EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(path, ownerOnly);
            } else {
                log.warn("POSIX permissions are not supported; ensure {} is readable only by the service "
                        + "account (on Windows restrict the ACL manually).", path);
            }
        } catch (IOException e) {
            log.warn("Could not restrict permissions on {}: {}", path, e.getMessage());
        }
    }

    /** Format marker kept for documentation/ops tooling. */
    public String keyFormat() {
        return KEY_FORMAT;
    }
}
