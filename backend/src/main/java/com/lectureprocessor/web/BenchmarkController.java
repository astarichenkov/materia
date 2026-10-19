package com.lectureprocessor.web;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.lectureprocessor.benchmark.BenchmarkComparison;
import com.lectureprocessor.benchmark.BenchmarkConfig;
import com.lectureprocessor.benchmark.BenchmarkPreset;
import com.lectureprocessor.benchmark.BenchmarkRun;
import com.lectureprocessor.benchmark.BenchmarkRunRequest;
import com.lectureprocessor.benchmark.BenchmarkRunService;
import com.lectureprocessor.benchmark.BenchmarkService;
import com.lectureprocessor.benchmark.BenchmarkUsageService;
import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.llm.LlmModelInfo;
import com.lectureprocessor.llm.ProviderDescriptor;
import com.lectureprocessor.repository.SegmentRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.text.PromptTemplates;

/**
 * Benchmark API (Day 29). Keeps the original {@code /config} + {@code /run}
 * endpoints for backward compatibility and adds the benchmark console API:
 * provider/model/prompt/preset discovery, asynchronous runs, history,
 * comparison and the recommended configuration.
 */
@RestController
@RequestMapping("/api/benchmarks")
public class BenchmarkController {

    private final BenchmarkService benchmarkService;
    private final BenchmarkRunService runService;
    private final BenchmarkUsageService usageService;
    private final LectureService lectureService;
    private final SegmentRepository segmentRepository;
    private final FileStorageService storage;

    public BenchmarkController(BenchmarkService benchmarkService, BenchmarkRunService runService,
            BenchmarkUsageService usageService, LectureService lectureService, SegmentRepository segmentRepository,
            FileStorageService storage) {
        this.benchmarkService = benchmarkService;
        this.runService = runService;
        this.usageService = usageService;
        this.lectureService = lectureService;
        this.segmentRepository = segmentRepository;
        this.storage = storage;
    }

    // --- Legacy API (kept for backward compatibility) ---

    @GetMapping("/config")
    public BenchmarkConfig config() {
        return benchmarkService.loadDefaultConfig();
    }

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> run(@RequestParam("lectureId") String lectureId,
            @RequestParam(name = "configPath", required = false) String configPath) {
        lectureService.getRequired(lectureId);
        BenchmarkConfig config = configPath == null
                ? benchmarkService.loadDefaultConfig()
                : benchmarkService.loadConfig(Path.of(configPath));
        String source = segmentRepository.findByLecture(lectureId).stream()
                .map(s -> s.text())
                .reduce("", (a, b) -> a + " " + b)
                .strip();
        if (source.isEmpty()) {
            throw new IllegalArgumentException("У материала нет транскрипта для бенчмарка");
        }
        Path dir = benchmarkService.runAndPersist(config, source);
        return Map.of("resultsDir", dir.toString());
    }

    // --- Benchmark console API ---

    @GetMapping("/providers")
    public List<ProviderDescriptor> providers() {
        return runService.providers();
    }

    @GetMapping("/models")
    public List<LlmModelInfo> models(@RequestParam(name = "provider", defaultValue = "ollama") String provider) {
        return runService.chatModels(provider);
    }

    @GetMapping("/prompts")
    public List<PromptTemplates.Template> prompts() {
        return runService.promptTemplates();
    }

    @GetMapping("/presets")
    public List<BenchmarkPreset> presets() {
        return runService.presets();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BenchmarkRun submit(@RequestBody BenchmarkRunRequest request) {
        lectureService.getRequired(request.lectureId());
        return runService.submit(request);
    }

    @GetMapping
    public List<BenchmarkRun> history(@RequestParam(name = "lectureId", required = false) String lectureId,
            @RequestParam(name = "limit", defaultValue = "25") int limit) {
        return lectureId == null
                ? runService.history(limit)
                : runService.historyForLecture(lectureId, limit);
    }

    @GetMapping("/compare")
    public BenchmarkComparison compare(@RequestParam("baseline") long baseline,
            @RequestParam("optimized") long optimized) {
        return runService.compare(baseline, optimized);
    }

    @GetMapping("/usage")
    public Map<String, Object> usage() {
        return usageService.summary();
    }

    @GetMapping("/{id}")
    public BenchmarkRun get(@PathVariable long id) {
        return runService.get(id).orElseThrow(
                () -> new IllegalArgumentException("Benchmark run не найден: " + id));
    }

    @PostMapping("/{id}/recommend")
    public BenchmarkRun recommend(@PathVariable long id) {
        return runService.recommend(id);
    }
}
