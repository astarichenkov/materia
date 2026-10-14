package com.lectureprocessor.benchmark;

import java.util.List;

import org.springframework.stereotype.Service;

import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.text.PromptTemplates;

/**
 * Named benchmark starting points based on the verified Day 29 measurements:
 * a heavy quality baseline and the optimized configuration actually chosen for
 * this hardware. {@code custom} means "keep whatever the user edited".
 */
@Service
public class BenchmarkPresetService {

    public List<BenchmarkPreset> presets() {
        return List.of(
                new BenchmarkPreset("baseline", "Baseline",
                        "qwen3:4b, thinking on, большой контекст/бюджет, structured-notes-v1",
                        new LlmRunConfig(LlmRunConfig.OLLAMA, "qwen3:4b", 0.1, 6144, 8192, true,
                                PromptTemplates.STRUCTURED_NOTES)),
                new BenchmarkPreset("optimized", "Optimized",
                        "qwen3:1.7b, thinking off, 4096 контекст / 1536 токенов, structured-notes-v1",
                        new LlmRunConfig(LlmRunConfig.OLLAMA, "qwen3:1.7b", 0.1, 1536, 4096, false,
                                PromptTemplates.STRUCTURED_NOTES)),
                new BenchmarkPreset("custom", "Custom",
                        "Пользовательские параметры (не перезаписываются)",
                        null));
    }

    public LlmRunConfig byId(String id) {
        return presets().stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .map(BenchmarkPreset::config)
                .orElse(null);
    }
}
