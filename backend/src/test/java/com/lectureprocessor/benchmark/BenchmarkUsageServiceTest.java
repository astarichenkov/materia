package com.lectureprocessor.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.CloudCostService;
import com.lectureprocessor.llm.LlmProvider;
import com.lectureprocessor.llm.LlmProviderRegistry;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.TestProperties;

/**
 * Cost/usage aggregation: real tokens, cloud request counting, estimate-only
 * costs and no monetary estimate for local providers.
 */
class BenchmarkUsageServiceTest {

    private BenchmarkRunRepository runs;
    private BenchmarkUsageService service;
    private FakeLlmProvider deepseek;
    private FakeLlmProvider ollama;

    @BeforeEach
    void setUp() {
        runs = mock(BenchmarkRunRepository.class);
        deepseek = new FakeLlmProvider();
        deepseek.setId("deepseek");
        deepseek.setCloud(true);
        ollama = new FakeLlmProvider();
        ollama.setId("ollama");

        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        when(registry.byId("deepseek")).thenReturn(java.util.Optional.of(deepseek));
        when(registry.byId("ollama")).thenReturn(java.util.Optional.of(ollama));

        AppProperties base = TestProperties.defaults("./target/test-data");
        AppProperties properties = new AppProperties(base.tools(), base.storage(), base.upload(),
                base.processing(), base.ollama(), base.slides(), base.chat(), base.llm(), base.downloader(),
                base.assistant(), base.mcp(), new AppProperties.DeepSeek(true, "https://api.deepseek.com",
                        "deepseek-flash", List.of("deepseek-flash"), java.time.Duration.ofSeconds(30), 0,
                        0.1, 512, 4096, 1, 0.0,
                        new AppProperties.DeepSeek.Pricing(0.5, 1.5, 0.05, "USD")),
                base.security());
        service = new BenchmarkUsageService(runs, registry, new CloudCostService(properties), properties);
    }

    private static BenchmarkRun run(Long id, String provider, String model, Integer input, Integer output,
            String status) {
        return new BenchmarkRun(id, "L1", provider, model, 0.1, 1024, 4096, false, "p", null,
                input, output, 0, 1, 1, 100L, 200L, 10.0, null, null, 5.0, null, status, null, null,
                false, Instant.now());
    }

    @Test
    void cloudRunsGetEstimatedCostAndLocalRunsDoNot() {
        when(runs.findAll(500)).thenReturn(List.of(
                run(1L, "deepseek", "deepseek-flash", 1_000_000, 1_000_000, "COMPLETED"),
                run(2L, "ollama", "qwen3:1.7b", 2_000_000, 2_000_000, "FAILED")));

        Map<String, Object> summary = service.summary();
        assertThat(summary.get("cloudRequests")).isEqualTo(1L);
        assertThat(summary.get("errors")).isEqualTo(1L);
        assertThat(summary.get("inputTokens")).isEqualTo(3_000_000L);
        assertThat((Double) summary.get("estimatedCost")).isEqualTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(summary.get("costIsEstimate")).isEqualTo(true);
        assertThat(summary.get("priced")).isEqualTo(true);
    }

    @Test
    void estimateForLocalProviderIsUnknown() {
        assertThat(service.estimateForRun(run(3L, "ollama", "qwen3:1.7b", 100, 200, "COMPLETED")).priced())
                .isFalse();
    }

    @Test
    void estimateForCloudProviderUsesConfiguredTariff() {
        CloudCostService.CostEstimate estimate =
                service.estimateForRun(run(4L, "deepseek", "deepseek-flash", 1_000_000, 1_000_000, "COMPLETED"));
        assertThat(estimate.priced()).isTrue();
        assertThat(estimate.amount()).isEqualTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(estimate.currency()).isEqualTo("USD");
    }

    @Test
    void unknownProviderIsNotTreatedAsCloud() {
        assertThat(service.isCloud("mystery")).isFalse();
    }
}
