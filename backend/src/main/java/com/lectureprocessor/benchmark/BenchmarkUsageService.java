package com.lectureprocessor.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.CloudCostService;
import com.lectureprocessor.llm.CloudCostService.CostEstimate;
import com.lectureprocessor.llm.DeepSeekLlmProvider;
import com.lectureprocessor.llm.LlmProviderRegistry;

/**
 * Aggregated usage and cost ESTIMATES for benchmark runs.
 *
 * <p>Only providers that actually report token usage are counted. Cloud spend is
 * always labelled an estimate because the tariff is configurable and the
 * provider's own billing is authoritative. Local (Ollama) runs never receive a
 * monetary estimate.</p>
 */
@Service
public class BenchmarkUsageService {

    private static final int DEFAULT_HISTORY = 500;

    private final BenchmarkRunRepository runs;
    private final LlmProviderRegistry registry;
    private final CloudCostService costs;
    private final AppProperties properties;

    public BenchmarkUsageService(BenchmarkRunRepository runs, LlmProviderRegistry registry,
            CloudCostService costs, AppProperties properties) {
        this.runs = runs;
        this.registry = registry;
        this.costs = costs;
        this.properties = properties;
    }

    /** Per-run estimate used by the UI (never fabricated for local providers). */
    public CostEstimate estimateForRun(BenchmarkRun run) {
        if (!isCloud(run.provider())) {
            return CostEstimate.unknown(costs.currency());
        }
        return costs.estimate(value(run.inputTokens()), value(run.outputTokens()), 0, 0);
    }

    public Map<String, Object> summary() {
        List<BenchmarkRun> history = runs.findAll(DEFAULT_HISTORY);
        long inputTokens = 0;
        long outputTokens = 0;
        long cloudRequests = 0;
        long errors = 0;
        double estimatedCost = 0;
        boolean anyPricedCloud = false;
        Map<String, Map<String, Object>> byModel = new LinkedHashMap<>();

        for (BenchmarkRun run : history) {
            boolean cloud = isCloud(run.provider());
            long in = value(run.inputTokens());
            long out = value(run.outputTokens());
            inputTokens += in;
            outputTokens += out;
            if (cloud) {
                cloudRequests++;
                CostEstimate estimate = estimateForRun(run);
                if (estimate.priced()) {
                    estimatedCost += estimate.amount();
                    anyPricedCloud = true;
                }
            }
            if (BenchmarkRunService.FAILED.equals(run.status())) {
                errors++;
            }
            String key = run.provider() + "/" + run.model();
            Map<String, Object> modelStats = byModel.computeIfAbsent(key, k -> {
                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("provider", run.provider());
                stats.put("model", run.model());
                stats.put("cloud", cloud);
                stats.put("requests", 0L);
                stats.put("inputTokens", 0L);
                stats.put("outputTokens", 0L);
                stats.put("errors", 0L);
                return stats;
            });
            increment(modelStats, "requests", 1);
            increment(modelStats, "inputTokens", in);
            increment(modelStats, "outputTokens", out);
            if (BenchmarkRunService.FAILED.equals(run.status())) {
                increment(modelStats, "errors", 1);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRequests", (long) history.size());
        summary.put("cloudRequests", cloudRequests);
        summary.put("inputTokens", inputTokens);
        summary.put("outputTokens", outputTokens);
        summary.put("errors", errors);
        summary.put("estimatedCost", estimatedCost);
        summary.put("currency", costs.currency());
        summary.put("costIsEstimate", true);
        summary.put("priced", anyPricedCloud);
        summary.put("budgetWarningCost", properties.deepseek().budgetWarningCost());
        summary.put("budgetWarningExceeded", properties.deepseek().budgetWarningCost() > 0
                && estimatedCost > properties.deepseek().budgetWarningCost());
        summary.put("byModel", new ArrayList<>(byModel.values()));
        return summary;
    }

    boolean isCloud(String providerId) {
        return registry.byId(providerId).map(provider -> provider.cloud()
                || DeepSeekLlmProvider.PROVIDER_ID.equals(provider.id())).orElse(false);
    }

    private static long value(Integer number) {
        return number == null ? 0 : number;
    }

    private static void increment(Map<String, Object> stats, String key, long delta) {
        stats.put(key, ((Number) stats.get(key)).longValue() + delta);
    }
}
