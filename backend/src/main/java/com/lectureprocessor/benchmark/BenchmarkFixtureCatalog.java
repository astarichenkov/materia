package com.lectureprocessor.benchmark;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads deterministic quality fixtures from the classpath. If a requested key is
 * unknown, the {@code default-lecture} spec is used so the benchmark always has
 * a rubric.
 */
@Component
public class BenchmarkFixtureCatalog {

    public static final String DEFAULT_KEY = "default-lecture";

    private final Map<String, QualitySpec> fixtures;

    public BenchmarkFixtureCatalog() {
        this.fixtures = load();
    }

    private static Map<String, QualitySpec> load() {
        try (InputStream in = BenchmarkFixtureCatalog.class
                .getResourceAsStream("/benchmark/fixtures.json")) {
            if (in == null) {
                return Map.of(DEFAULT_KEY, QualitySpec.empty());
            }
            Map<String, QualitySpec> parsed = new ObjectMapper()
                    .readValue(in, new TypeReference<LinkedHashMap<String, QualitySpec>>() {
                    });
            return parsed.isEmpty() ? Map.of(DEFAULT_KEY, QualitySpec.empty()) : parsed;
        } catch (Exception e) {
            return Map.of(DEFAULT_KEY, QualitySpec.empty());
        }
    }

    public QualitySpec spec(String key) {
        QualitySpec spec = key == null ? null : fixtures.get(key);
        if (spec != null) {
            return spec;
        }
        return fixtures.getOrDefault(DEFAULT_KEY, QualitySpec.empty());
    }

    public Set<String> keys() {
        return fixtures.keySet();
    }

    public List<String> keyList() {
        return fixtures.keySet().stream().sorted().toList();
    }
}
