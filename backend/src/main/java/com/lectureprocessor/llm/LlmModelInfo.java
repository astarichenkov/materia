package com.lectureprocessor.llm;

import java.util.List;

/**
 * Runtime metadata about one model exposed by a provider. Used by the benchmark
 * UI to offer real model choices (with quantization and capabilities) instead of
 * a hardcoded list.
 *
 * @param name          provider-specific model id, e.g. {@code qwen3:1.7b}
 * @param family        model family, e.g. {@code qwen3} (may be null)
 * @param parameterSize human-readable parameter count, e.g. {@code 2.0B}
 * @param quantization  quantization level, e.g. {@code Q4_K_M} (may be null)
 * @param contextLength declared context window (may be null)
 * @param capabilities  provider capabilities, e.g. {@code [completion, thinking]}
 * @param sizeBytes     on-disk size (may be null)
 */
public record LlmModelInfo(
        String name,
        String family,
        String parameterSize,
        String quantization,
        Long contextLength,
        List<String> capabilities,
        Long sizeBytes) {

    public LlmModelInfo {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    /** A model that can be used as a chat/generation model. */
    public boolean chatCapable() {
        if (capabilities.isEmpty()) {
            // Unknown capabilities: treat as chat-capable unless it is clearly an embedder.
            return name != null && !name.contains("embed");
        }
        return capabilities.contains("completion") || capabilities.contains("chat");
    }

    /** A model that can produce embeddings. */
    public boolean embeddingCapable() {
        return capabilities.contains("embedding") || (name != null && name.contains("embed"));
    }

    public String quantizationOrUnknown() {
        return quantization == null || quantization.isBlank() ? "N/A" : quantization;
    }
}
