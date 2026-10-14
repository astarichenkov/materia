package com.lectureprocessor.llm;

import java.util.List;

/**
 * A selectable LLM provider plus its discoverable models. Cloud providers that
 * are not configured appear with {@code available=false} so the UI can show them
 * as disabled without a real implementation.
 */
public record ProviderDescriptor(
        String id,
        String displayName,
        boolean available,
        boolean cloud,
        List<LlmModelInfo> models) {

    public ProviderDescriptor {
        models = models == null ? List.of() : List.copyOf(models);
    }

    public List<LlmModelInfo> chatModels() {
        return models.stream().filter(LlmModelInfo::chatCapable).toList();
    }
}
