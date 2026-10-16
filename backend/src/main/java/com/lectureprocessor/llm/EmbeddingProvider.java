package com.lectureprocessor.llm;

import java.util.List;

/**
 * Embedding capability, deliberately separated from {@link LlmProvider}.
 *
 * <p>Chat/generation and embeddings have different failure modes and different
 * privacy implications. A cloud chat provider (e.g. DeepSeek) must never be
 * silently used for embeddings, and an embedding failure must not be hidden
 * behind a chat provider. Only providers that genuinely compute vectors
 * implement this interface.</p>
 */
public interface EmbeddingProvider {

    /** Stable provider id, e.g. {@code ollama}. */
    String id();

    /** Embeds a batch of texts, returning L2-normalized vectors. */
    List<float[]> embed(List<String> texts);

    /** Cheap reachability check used for health reporting and auto-indexing. */
    boolean isAvailable();
}
