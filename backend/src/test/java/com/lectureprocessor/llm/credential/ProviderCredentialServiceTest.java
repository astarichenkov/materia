package com.lectureprocessor.llm.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Base64;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.support.TestProperties;

/**
 * Verifies encrypted-at-rest credential storage: AES-GCM round-trip, replace,
 * delete, cross-installation isolation, master-key resolution and that the
 * plaintext never appears in the stored ciphertext.
 */
class ProviderCredentialServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = sqlite(tempDir.resolve("credentials.db"));
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
        }
    }

    private ProviderCredentialService service(String masterKeyPath) {
        AppProperties base = TestProperties.defaults(tempDir.toString());
        AppProperties props = withSecurity(base, masterKeyPath, "NONEXISTENT_MASTER_KEY_" + masterKeyPath.hashCode());
        MasterKeyStore keys = new MasterKeyStore(props);
        return new ProviderCredentialService(new ProviderCredentialRepository(jdbc),
                new CredentialCipher(keys), keys);
    }

    private static AppProperties withSecurity(AppProperties base, String keyPath, String envName) {
        return new AppProperties(base.tools(), base.storage(), base.upload(), base.processing(),
                base.ollama(), base.slides(), base.chat(), base.llm(), base.downloader(), base.assistant(),
                base.mcp(), base.deepseek(), new AppProperties.Security("", keyPath, envName),
                base.gitea(), base.codeReview());
    }

    private static DataSource sqlite(Path path) {
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + path.toAbsolutePath());
        return dataSource;
    }

    @Test
    void storesEncryptedAndResolvesOriginalKey() {
        ProviderCredentialService service = service(tempDir.resolve("k1/master.key").toString());
        service.save("deepseek", "sk-secret-value-1234");

        String stored = jdbc.queryForObject(
                "SELECT ciphertext FROM provider_credential WHERE provider_id = 'deepseek'", String.class);
        assertThat(stored).startsWith("v1:");
        assertThat(stored).doesNotContain("sk-secret-value-1234");
        assertThat(service.isConfigured("deepseek")).isTrue();
        assertThat(service.resolveKey("deepseek")).contains("sk-secret-value-1234");
        assertThat(service.status("deepseek").configured()).isTrue();
        assertThat(service.status("deepseek").keyHint()).endsWith("1234");
    }

    @Test
    void replaceProducesDifferentCiphertextAndKeepsNewKey() {
        ProviderCredentialService service = service(tempDir.resolve("k2/master.key").toString());
        service.save("deepseek", "sk-first-key-0001");
        String first = jdbc.queryForObject(
                "SELECT ciphertext FROM provider_credential WHERE provider_id = 'deepseek'", String.class);

        service.save("deepseek", "sk-second-key-0002");
        String second = jdbc.queryForObject(
                "SELECT ciphertext FROM provider_credential WHERE provider_id = 'deepseek'", String.class);

        assertThat(second).isNotEqualTo(first);
        assertThat(service.resolveKey("deepseek")).contains("sk-second-key-0002");
    }

    @Test
    void deleteRemovesCredential() {
        ProviderCredentialService service = service(tempDir.resolve("k3/master.key").toString());
        service.save("deepseek", "sk-to-delete-9999");
        assertThat(service.delete("deepseek")).isTrue();
        assertThat(service.isConfigured("deepseek")).isFalse();
        assertThat(service.resolveKey("deepseek")).isEmpty();
    }

    @Test
    void emptyKeyIsRejected() {
        ProviderCredentialService service = service(tempDir.resolve("k4/master.key").toString());
        assertThatThrownBy(() -> service.save("deepseek", "   "))
                .isInstanceOf(CredentialException.class);
    }

    @Test
    void anotherInstallationCannotDecryptCredentials() {
        ProviderCredentialService first = service(tempDir.resolve("install-a/master.key").toString());
        first.save("deepseek", "sk-install-a-secret");

        // Same database, a different per-installation master key.
        ProviderCredentialService second = service(tempDir.resolve("install-b/master.key").toString());
        assertThat(second.isConfigured("deepseek")).isTrue();
        assertThatThrownBy(() -> second.resolveKey("deepseek"))
                .isInstanceOf(CredentialException.class);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        ProviderCredentialService service = service(tempDir.resolve("k5/master.key").toString());
        service.save("deepseek", "sk-tamper-me-please");
        String stored = jdbc.queryForObject(
                "SELECT ciphertext FROM provider_credential WHERE provider_id = 'deepseek'", String.class);
        String corrupted = stored.substring(0, stored.length() - 4) + "AAAA";
        jdbc.update("UPDATE provider_credential SET ciphertext = ? WHERE provider_id = 'deepseek'", corrupted);

        assertThatThrownBy(() -> service.resolveKey("deepseek"))
                .isInstanceOf(CredentialException.class);
    }

    @Test
    void externalEnvironmentMasterKeyIsPreferred() {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        String envName = "LP_TEST_MASTER_KEY";
        System.setProperty(envName, Base64.getEncoder().encodeToString(raw));
        try {
            AppProperties base = TestProperties.defaults(tempDir.toString());
            AppProperties props = withSecurity(base, tempDir.resolve("unused/master.key").toString(), envName);
            MasterKeyStore keys = new MasterKeyStore(props);
            ProviderCredentialService service = new ProviderCredentialService(
                    new ProviderCredentialRepository(jdbc), new CredentialCipher(keys), keys);
            service.save("deepseek", "sk-env-master-key-123");

            assertThat(service.resolveKey("deepseek")).contains("sk-env-master-key-123");
            assertThat(keys.usesExternalKey()).isTrue();
            assertThat(service.usingFallbackMasterKey()).isFalse();
            assertThat(Files.exists(Path.of(tempDir.resolve("unused/master.key").toString()))).isFalse();
        } finally {
            System.clearProperty(envName);
        }
    }
}
