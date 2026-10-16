package com.lectureprocessor.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.credential.ProviderCredentialService;
import com.lectureprocessor.repository.AppSettingRepository;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.TestProperties;
import com.lectureprocessor.text.PromptTemplates;

import tools.jackson.databind.ObjectMapper;

/**
 * Per-task model routing: isolation between tasks, persistence, defaults and
 * explicit fallback behaviour.
 */
class LlmTaskSettingsServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private LlmProviderRegistry registry;
    private LlmSettingsService lectureSettings;
    private ProviderCredentialService credentials;
    private LlmTaskSettingsService service;
    private FakeLlmProvider ollama;
    private FakeLlmProvider deepseek;
    private AppProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = sqlite(tempDir.resolve("tasks.db"));
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
        }
        properties = TestProperties.defaults(tempDir.toString());
        ollama = new FakeLlmProvider();
        ollama.setId("ollama");
        ollama.setModels(List.of(new LlmModelInfo("qwen3:1.7b", "qwen3", "2.0B", "Q4_K_M", 40960L,
                List.of("completion"), 1L)));
        deepseek = new FakeLlmProvider();
        deepseek.setId("deepseek");
        deepseek.setCloud(true);
        deepseek.setModels(List.of(new LlmModelInfo("deepseek-flash", "deepseek", null, null, 8192L,
                List.of("completion", "thinking"), null)));

        registry = new LlmProviderRegistry(List.of(ollama, deepseek), properties);
        credentials = mock(ProviderCredentialService.class);
        lectureSettings = new LlmSettingsService(new AppSettingRepository(jdbc), registry, properties,
                new ObjectMapper());
        service = new LlmTaskSettingsService(new AppSettingRepository(jdbc), lectureSettings, registry,
                properties, credentials, new ObjectMapper());
    }

    @Test
    void lectureProcessingDelegatesToExistingDefaultConfig() {
        lectureSettings.save(config("ollama", "qwen3:1.7b", 0.2, 4096, 8192));
        assertThat(service.configFor(LlmTaskType.LECTURE_PROCESSING).model()).isEqualTo("qwen3:1.7b");
    }

    @Test
    void tasksAreIsolatedFromEachOther() {
        service.save(LlmTaskType.LECTURE_PROCESSING, config("ollama", "qwen3:1.7b", 0.1, 1536, 4096));
        service.save(LlmTaskType.DEVELOPER_ASSISTANT, config("deepseek", "deepseek-flash", 0.3, 2048, 8192));
        service.save(LlmTaskType.CODE_REVIEW, config("deepseek", "deepseek-flash", 0.0, 2048, 8192));

        assertThat(service.configFor(LlmTaskType.LECTURE_PROCESSING).model()).isEqualTo("qwen3:1.7b");
        assertThat(service.configFor(LlmTaskType.DEVELOPER_ASSISTANT).model()).isEqualTo("deepseek-flash");
        assertThat(service.configFor(LlmTaskType.CODE_REVIEW).temperature()).isZero();

        // Changing code review must not touch lecture processing.
        service.save(LlmTaskType.CODE_REVIEW, config("ollama", "qwen3:1.7b", 0.9, 512, 4096));
        assertThat(service.configFor(LlmTaskType.LECTURE_PROCESSING).model()).isEqualTo("qwen3:1.7b");
    }

    @Test
    void codeReviewDefaultsToDeepSeekWhenCredentialConfigured() {
        when(credentials.isConfigured("deepseek")).thenReturn(true);
        LlmRunConfig config = service.configFor(LlmTaskType.CODE_REVIEW);
        assertThat(config.provider()).isEqualTo("deepseek");
        assertThat(config.model()).isEqualTo("deepseek-flash");
    }

    @Test
    void codeReviewFallsBackToOllamaWithoutCredential() {
        when(credentials.isConfigured("deepseek")).thenReturn(false);
        LlmRunConfig config = service.configFor(LlmTaskType.CODE_REVIEW);
        assertThat(config.provider()).isEqualTo("ollama");
    }

    @Test
    void resolveReturnsRegisteredProviderAndOptions() {
        service.save(LlmTaskType.DEVELOPER_ASSISTANT, config("deepseek", "deepseek-flash", 0.4, 2048, 8192));
        ResolvedLlmTask resolved = service.resolve(LlmTaskType.DEVELOPER_ASSISTANT);

        assertThat(resolved.provider()).isSameAs(deepseek);
        assertThat(resolved.options().model()).isEqualTo("deepseek-flash");
        assertThat(resolved.options().temperature()).isEqualTo(0.4);
        assertThat(resolved.cloud()).isTrue();
        assertThat(resolved.fallback()).isFalse();
    }

    @Test
    void resolveFallsBackExplicitlyWhenProviderUnknown() {
        service.save(LlmTaskType.DEVELOPER_ASSISTANT, config("ghost-provider", "ghost", 0.1, 1024, 4096));
        ResolvedLlmTask resolved = service.resolve(LlmTaskType.DEVELOPER_ASSISTANT);

        assertThat(resolved.provider()).isSameAs(ollama);
        assertThat(resolved.fallback()).isTrue();
        assertThat(resolved.fallbackReason()).isNotBlank();
    }

    @Test
    void invalidSettingsAreRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.save(LlmTaskType.CODE_REVIEW,
                        new LlmRunConfig("ollama", "m", 5.0, 1536, 4096, false, PromptTemplates.STRUCTURED_NOTES)))
                .isInstanceOf(InvalidLlmSettingsException.class);
    }

    private static LlmRunConfig config(String provider, String model, double temperature, int maxTokens,
            int context) {
        return new LlmRunConfig(provider, model, temperature, maxTokens, context, false,
                PromptTemplates.STRUCTURED_NOTES);
    }

    private static DataSource sqlite(Path path) {
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + path.toAbsolutePath());
        return dataSource;
    }
}
