package com.lectureprocessor.benchmark;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.llm.LlmException;
import com.lectureprocessor.llm.LlmModelInfo;
import com.lectureprocessor.llm.LlmProvider;
import com.lectureprocessor.llm.LlmProviderRegistry;
import com.lectureprocessor.llm.LlmResult;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmSettingsService;
import com.lectureprocessor.llm.LlmSettingsValidator;
import com.lectureprocessor.llm.ProviderDescriptor;
import com.lectureprocessor.repository.SegmentRepository;
import com.lectureprocessor.repository.TimingRepository;
import com.lectureprocessor.text.PromptTemplates;
import com.lectureprocessor.text.SegmentChunk;
import com.lectureprocessor.text.TextChunker;

/**
 * Benchmark console: runs the LLM stage of the pipeline for a chosen lecture and
 * configuration, persists the measured result and supports history, comparison
 * and the recommended configuration. Runs asynchronously so the HTTP request
 * returns immediately.
 */
@Service
public class BenchmarkRunService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunService.class);

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    private final LlmProviderRegistry registry;
    private final LlmSettingsService settings;
    private final BenchmarkRunRepository runs;
    private final BenchmarkFixtureCatalog fixtures;
    private final BenchmarkPresetService presets;
    private final QualityEvaluator quality;
    private final PromptTemplates prompts;
    private final TextChunker chunker;
    private final SegmentRepository segments;
    private final TimingRepository timings;
    private final ResourceMonitor resources;
    private final AppProperties properties;
    private final ThreadPoolTaskExecutor executor;

    public BenchmarkRunService(LlmProviderRegistry registry, LlmSettingsService settings,
            BenchmarkRunRepository runs, BenchmarkFixtureCatalog fixtures, BenchmarkPresetService presets,
            QualityEvaluator quality, PromptTemplates prompts, TextChunker chunker,
            SegmentRepository segments, TimingRepository timings, ResourceMonitor resources,
            AppProperties properties, @Qualifier("benchmarkExecutor") ThreadPoolTaskExecutor executor) {
        this.registry = registry;
        this.settings = settings;
        this.runs = runs;
        this.fixtures = fixtures;
        this.presets = presets;
        this.quality = quality;
        this.prompts = prompts;
        this.chunker = chunker;
        this.segments = segments;
        this.timings = timings;
        this.resources = resources;
        this.properties = properties;
        this.executor = executor;
    }

    public List<ProviderDescriptor> providers() {
        return registry.providers();
    }

    public List<LlmModelInfo> chatModels(String provider) {
        return registry.chatModels(provider);
    }

    public List<PromptTemplates.Template> promptTemplates() {
        return prompts.list();
    }

    public List<BenchmarkPreset> presets() {
        return presets.presets();
    }

    public LlmRunConfig currentSettings() {
        return settings.current();
    }

    public void saveSettings(LlmRunConfig config) {
        LlmSettingsValidator.validate(config);
        settings.save(config);
    }

    /** Persists a QUEUED run and submits it to the benchmark executor. */
    public BenchmarkRun submit(BenchmarkRunRequest request) {
        if (request == null || request.config() == null) {
            throw new IllegalArgumentException("Не задана конфигурация бенчмарка");
        }
        LlmSettingsValidator.validate(request.config());
        LlmRunConfig config = request.config();
        BenchmarkRun queued = new BenchmarkRun(null, request.lectureId(), config.provider(), config.model(),
                config.temperature(), config.maxTokens(), config.contextWindow(), config.thinking(),
                config.promptTemplate(), quantizationOf(config), null, null, null, null, null, null, null,
                null, null, null, null, null, QUEUED, null, null, false, Instant.now());
        BenchmarkRun saved = runs.save(queued);
        try {
            executor.execute(() -> {
                try {
                    execute(saved.id(), request);
                } catch (Exception e) {
                    log.error("Benchmark run {} ended with error: {}", saved.id(), e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            runs.save(saved.withStatus(FAILED));
            throw new IllegalStateException("Не удалось поставить бенчмарк в очередь", e);
        }
        return saved;
    }

    /** Runs one benchmark synchronously (called by the executor; also unit-testable). */
    public void execute(long runId, BenchmarkRunRequest request) {
        BenchmarkRun run = runs.findById(runId).orElseThrow(
                () -> new IllegalArgumentException("Benchmark run не найден: " + runId));
        runs.save(run.withStatus(RUNNING));
        LlmRunConfig config = request.config();
        try {
            LlmProvider provider = registry.require(config.provider());
            if (!provider.isAvailable()) {
                throw new LlmException("LLM provider недоступен: " + config.provider());
            }
            List<TranscriptSegment> lectureSegments = segments.findByLecture(request.lectureId());
            if (lectureSegments.isEmpty()) {
                throw new IllegalArgumentException("У лекции нет транскрипта для бенчмарка");
            }
            int maxChars = properties.processing().llmChunkChars();
            int overlap = properties.processing().llmChunkOverlapChars();
            List<SegmentChunk> chunks = chunker.chunkSegments(lectureSegments, maxChars, overlap);
            String system = prompts.get(config.promptTemplate());
            boolean notesStyle = config.promptTemplate().contains("notes");

            int inputTokens = 0;
            int outputTokens = 0;
            int thinkingTokens = 0;
            boolean truncated = false;
            StringBuilder merged = new StringBuilder();
            long llmStart = System.nanoTime();
            // Cloud providers report token metrics but no local GPU/RAM: never
            // present fabricated hardware numbers for a cloud model.
            boolean cloud = provider.cloud();
            ResourceMonitor.Sampler sampler = cloud ? null : resources.startSampler();
            try {
                int index = 0;
                for (SegmentChunk chunk : chunks) {
                    String user = notesStyle
                            ? "Фрагмент " + (++index) + " из " + chunks.size() + ":\n\n" + chunk.text()
                            : chunk.text();
                    LlmResult result = provider.generate(system, user, config.toOptions());
                    inputTokens += result.inputTokens();
                    outputTokens += result.outputTokens();
                    thinkingTokens += result.thinkingTokens();
                    truncated |= result.truncated();
                    if (result.content() != null && !result.content().isBlank()) {
                        merged.append(result.content()).append("\n\n");
                    }
                }
            } finally {
                if (sampler != null) {
                    sampler.close();
                }
            }
            long llmDurationMs = (System.nanoTime() - llmStart) / 1_000_000;
            String output = merged.toString().strip();

            QualityResult qualityResult = quality.evaluate(output, fixtures.spec(request.fixtureKey()));
            if (request.manualScore() != null) {
                qualityResult = new QualityResult(request.manualScore(), qualityResult.notes());
            }
            String qualityNotes = qualityResult.notesText() + (truncated ? "; truncated(done=length)" : "");
            double tokensPerSecond = llmDurationMs > 0 && outputTokens > 0
                    ? round(outputTokens / (llmDurationMs / 1000.0)) : 0;
            Long totalDurationMs = estimatedFullConversion(
                    timings.findByLecture(request.lectureId()), llmDurationMs);
            Long peakRamMb = sampler == null ? null : sampler.peakRamMb();
            Long peakVramMb = sampler == null ? null : sampler.peakVramMb();

            BenchmarkRun completed = run.withResult(inputTokens, outputTokens, thinkingTokens,
                            chunks.size(), chunks.size(), llmDurationMs, tokensPerSecond,
                            peakRamMb, peakVramMb, qualityResult.score(),
                            qualityNotes, COMPLETED, null, truncate(output, 8000))
                    .withTotalDuration(totalDurationMs);
            runs.save(completed);
            if (cloud && properties.deepseek().budgetWarningCost() > 0) {
                log.info("Cloud benchmark run {} completed with {} input / {} output tokens; "
                        + "check /api/benchmarks/usage against the configured budget warning level",
                        runId, inputTokens, outputTokens);
            }
            log.info("Benchmark run {} completed: model={} llmMs={} outTok={} quality={} cloud={}",
                    runId, config.model(), llmDurationMs, outputTokens, qualityResult.score(), cloud);
        } catch (Exception e) {
            log.warn("Benchmark run {} failed: {}", runId, e.getMessage());
            runs.save(run.withResult(null, null, null, null, null, null, null, null, null, null, null,
                    FAILED, e.getMessage(), null));
        }
    }

    public Optional<BenchmarkRun> get(long id) {
        return runs.findById(id);
    }

    public List<BenchmarkRun> history(int limit) {
        return runs.findAll(limit);
    }

    public List<BenchmarkRun> historyForLecture(String lectureId, int limit) {
        return runs.findByLecture(lectureId, limit);
    }

    public Optional<BenchmarkRun> recommended() {
        return runs.findRecommended();
    }

    /** Marks a run as recommended and stores its config as the default for new jobs. */
    public BenchmarkRun recommend(long id) {
        BenchmarkRun run = runs.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Benchmark run не найден: " + id));
        runs.markRecommended(id);
        settings.recommend(run);
        return runs.findById(id).orElse(run);
    }

    public BenchmarkComparison compare(long baselineId, long optimizedId) {
        BenchmarkRun baseline = runs.findById(baselineId)
                .orElseThrow(() -> new IllegalArgumentException("Baseline run не найден: " + baselineId));
        BenchmarkRun optimized = runs.findById(optimizedId)
                .orElseThrow(() -> new IllegalArgumentException("Optimized run не найден: " + optimizedId));
        double llmSpeedup = speedup(baseline.llmDurationMs(), optimized.llmDurationMs());
        double totalSpeedup = speedup(baseline.totalDurationMs(), optimized.totalDurationMs());
        Double qualityDelta = delta(optimized.qualityScore(), baseline.qualityScore());
        Long ramDelta = delta(optimized.peakRamMb(), baseline.peakRamMb());
        Long vramDelta = delta(optimized.peakVramMb(), baseline.peakVramMb());

        List<String> summary = new ArrayList<>();
        if (llmSpeedup > 0) {
            summary.add(String.format("LLM stage: %.1fx быстрее (%s → %s мс)",
                    llmSpeedup, baseline.llmDurationMs(), optimized.llmDurationMs()));
        }
        if (qualityDelta != null) {
            summary.add(String.format("Quality: %.1f → %.1f", baseline.qualityScore(), optimized.qualityScore()));
        }
        if (baseline.model() != null) {
            summary.add(baseline.model() + " → " + optimized.model());
        }
        return new BenchmarkComparison(baseline, optimized, round(llmSpeedup), round(totalSpeedup),
                qualityDelta, ramDelta, vramDelta, String.join("; ", summary));
    }

    private String quantizationOf(LlmRunConfig config) {
        return registry.byId(config.provider())
                .map(LlmProvider::listModels)
                .orElse(List.of()).stream()
                .filter(m -> m.name() != null && m.name().equals(config.model()))
                .map(LlmModelInfo::quantizationOrUnknown)
                .findFirst()
                .orElse(null);
    }

    private static double speedup(Long baselineMs, Long optimizedMs) {
        if (baselineMs == null || optimizedMs == null || optimizedMs <= 0) {
            return 0;
        }
        return (double) baselineMs / optimizedMs;
    }

    /**
     * Estimated full conversion time for this run: the measured non-LLM overhead
     * of the last pipeline execution (FFmpeg + Whisper + export + overhead) plus
     * this run's LLM duration. Returns {@code null} when no stage timings exist.
     */
    private static Long estimatedFullConversion(java.util.Map<String, Long> stageTimings, long llmDurationMs) {
        Long total = stageTimings.get(TimingRepository.TOTAL);
        Long storedLlm = stageTimings.get(TimingRepository.LLM);
        if (total == null || storedLlm == null) {
            return total;
        }
        long overhead = Math.max(0, total - storedLlm);
        return overhead + llmDurationMs;
    }

    private static Double delta(Double optimized, Double baseline) {
        return optimized == null || baseline == null ? null : optimized - baseline;
    }

    private static Long delta(Long optimized, Long baseline) {
        return optimized == null || baseline == null ? null : optimized - baseline;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
