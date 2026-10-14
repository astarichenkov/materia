package com.lectureprocessor.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.llm.LlmModelInfo;
import com.lectureprocessor.llm.LlmProviderRegistry;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmSettingsService;
import com.lectureprocessor.repository.AppSettingRepository;
import com.lectureprocessor.repository.SegmentRepository;
import com.lectureprocessor.repository.TimingRepository;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.TestProperties;
import com.lectureprocessor.text.PromptTemplates;
import com.lectureprocessor.text.TextChunker;

import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end (no Spring context, real SQLite schema) test of the benchmark
 * console: request → execution → persistence → comparison → recommended config.
 */
class BenchmarkRunServiceTest {

    private static final String LECTURE = "lecture-1";
    private static final String NOTES_OUTPUT = """
            ## Векторная алгебра

            - Вектор — направленный отрезок.
            - Скалярное произведение и перпендикулярность.
            - Векторное и смешанное произведение.
            - Применение в машинном обучении, нормализация.
            """;

    private Path tempDir;
    private FakeLlmProvider llm;
    private BenchmarkRunRepository runs;
    private LlmSettingsService settings;
    private BenchmarkRunService service;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("benchmark-test");
        DataSource dataSource = sqlite(tempDir.resolve("test.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
        }

        AppProperties properties = TestProperties.defaults(tempDir.toString());
        runs = new BenchmarkRunRepository(jdbc);
        AppSettingRepository appSettings = new AppSettingRepository(jdbc);
        TimingRepository timings = new TimingRepository(jdbc);
        SegmentRepository segments = new SegmentRepository(jdbc);
        segments.replaceAll(LECTURE, List.of(
                new TranscriptSegment(0, 0, 1000, "Вектор — направленный отрезок."),
                new TranscriptSegment(1, 1000, 2000, "Скалярное произведение и перпендикулярность.")));

        llm = new FakeLlmProvider();
        llm.setDetailed(true);
        llm.setTokenUsage(11, 22);
        llm.setDurationMsPerCall(5);
        llm.setChatFunction((system, user) -> NOTES_OUTPUT);
        llm.setModels(List.of(new LlmModelInfo("qwen3:1.7b", "qwen3", "2.0B", "Q4_K_M",
                40960L, List.of("completion"), 1_000_000L)));

        LlmProviderRegistry registry = new LlmProviderRegistry(List.of(llm), properties);
        settings = new LlmSettingsService(appSettings, registry, properties, new ObjectMapper());

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();

        service = new BenchmarkRunService(registry, settings, runs, new BenchmarkFixtureCatalog(),
                new BenchmarkPresetService(), new QualityEvaluator(), new PromptTemplates(), new TextChunker(),
                segments, timings, new ResourceMonitor(), properties, executor);
    }

    private static DataSource sqlite(Path path) {
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + path.toAbsolutePath());
        return dataSource;
    }

    private static LlmRunConfig optimizedConfig() {
        return new LlmRunConfig("ollama", "qwen3:1.7b", 0.1, 1536, 4096, false, "structured-notes-v1");
    }

    private long queueRun(BenchmarkRunRequest request) {
        BenchmarkRun run = new BenchmarkRun(null, request.lectureId(), request.config().provider(),
                request.config().model(), request.config().temperature(), request.config().maxTokens(),
                request.config().contextWindow(), request.config().thinking(),
                request.config().promptTemplate(), null, null, null, null, null, null, null, null,
                null, null, null, null, null, "QUEUED", null, null, false, Instant.now());
        return runs.save(run).id();
    }

    @Test
    void executesBenchmarkPersistsResultAndPropagatesParameters() {
        LlmRunConfig config = optimizedConfig();
        BenchmarkRunRequest request = new BenchmarkRunRequest(LECTURE, "default-lecture", config, null);
        long id = queueRun(request);

        service.execute(id, request);

        BenchmarkRun stored = runs.findById(id).orElseThrow();
        assertThat(stored.status()).isEqualTo(BenchmarkRunService.COMPLETED);
        assertThat(stored.outputTokens()).isPositive();
        assertThat(stored.inputTokens()).isPositive();
        assertThat(stored.llmCalls()).isEqualTo(stored.chunkCount());
        assertThat(stored.qualityScore()).isGreaterThanOrEqualTo(4.0);
        assertThat(stored.output()).contains("Вектор");

        // Parameter propagation into the provider request.
        assertThat(llm.optionCalls()).isNotEmpty();
        var options = llm.optionCalls().getLast();
        assertThat(options.model()).isEqualTo("qwen3:1.7b");
        assertThat(options.temperature()).isEqualTo(0.1);
        assertThat(options.maxTokens()).isEqualTo(1536);
        assertThat(options.numCtx()).isEqualTo(4096);
        assertThat(options.thinking()).isFalse();
    }

    @Test
    void submitDiscoversQuantizationAndRunsAsynchronously() throws Exception {
        LlmRunConfig config = optimizedConfig();
        BenchmarkRun queued = service.submit(new BenchmarkRunRequest(LECTURE, "default-lecture", config, null));
        assertThat(queued.status()).isEqualTo(BenchmarkRunService.QUEUED);

        BenchmarkRun finished = awaitTerminal(queued.id());
        assertThat(finished.status()).isEqualTo(BenchmarkRunService.COMPLETED);
        assertThat(finished.quantization()).isEqualTo("Q4_K_M");
    }

    private BenchmarkRun awaitTerminal(long id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            BenchmarkRun run = runs.findById(id).orElseThrow();
            if (run.status().equals(BenchmarkRunService.COMPLETED)
                    || run.status().equals(BenchmarkRunService.FAILED)) {
                return run;
            }
            Thread.sleep(50);
        }
        return runs.findById(id).orElseThrow();
    }

    @Test
    void marksRunFailedWhenProviderUnavailable() {
        llm.setAvailable(false);
        LlmRunConfig config = optimizedConfig();
        BenchmarkRunRequest request = new BenchmarkRunRequest(LECTURE, "default-lecture", config, null);
        long id = queueRun(request);

        service.execute(id, request);

        BenchmarkRun stored = runs.findById(id).orElseThrow();
        assertThat(stored.status()).isEqualTo(BenchmarkRunService.FAILED);
        assertThat(stored.error()).contains("недоступен");
    }

    @Test
    void comparesBaselineAndOptimizedWithSpeedupAndQualityDelta() {
        long baseline = runs.save(completedRun("qwen3:4b", 187_000L, 4.9, 3000L, 3800L, 3600L)).id();
        long optimized = runs.save(completedRun("qwen3:1.7b", 5_800L, 4.8, 2200L, 3000L, 2200L)).id();

        BenchmarkComparison comparison = service.compare(baseline, optimized);

        assertThat(comparison.llmSpeedup()).isGreaterThan(30.0);
        assertThat(comparison.qualityDelta()).isCloseTo(-0.1, org.assertj.core.data.Offset.offset(0.001));
        assertThat(comparison.ramDeltaMb()).isEqualTo(-800L);
        assertThat(comparison.summary()).contains("qwen3:4b → qwen3:1.7b");
    }

    @Test
    void recommendStoresConfigurationAsDefault() {
        long id = runs.save(completedRun("qwen3:1.7b", 5_800L, 4.8, 2200L, 3000L, 2200L)).id();

        service.recommend(id);

        assertThat(runs.findRecommended().orElseThrow().id()).isEqualTo(id);
        LlmRunConfig current = settings.current();
        assertThat(current.model()).isEqualTo("qwen3:1.7b");
        assertThat(current.temperature()).isEqualTo(0.1);
        assertThat(current.contextWindow()).isEqualTo(4096);
        assertThat(current.maxTokens()).isEqualTo(1536);
    }

    private static BenchmarkRun completedRun(String model, long llmDurationMs, double quality,
            Long totalMs, Long peakRamMb, Long peakVramMb) {
        return new BenchmarkRun(null, LECTURE, "ollama", model, 0.1, 1536, 4096, false,
                "structured-notes-v1", "Q4_K_M", 100, 200, 0, 1, 1, llmDurationMs, totalMs,
                110.0, peakRamMb, peakVramMb, quality, "ok", "COMPLETED", null, "out", false,
                Instant.now());
    }
}
