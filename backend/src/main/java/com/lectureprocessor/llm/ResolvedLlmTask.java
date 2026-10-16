package com.lectureprocessor.llm;

/**
 * Fully resolved LLM configuration for one task at one point in time. Callers
 * receive both the provider and the options so a job can snapshot them and is
 * never affected by later settings changes.
 *
 * @param task           logical task the configuration belongs to
 * @param config         persisted/provider-independent configuration
 * @param provider       concrete provider to call
 * @param options        generation options derived from the configuration
 * @param fallback       true when the primary provider was replaced (never silent)
 * @param fallbackReason human-readable reason for the fallback (null when primary)
 * @param cloud          true when the resolved provider is a cloud provider
 */
public record ResolvedLlmTask(
        LlmTaskType task,
        LlmRunConfig config,
        LlmProvider provider,
        LlmOptions options,
        boolean fallback,
        String fallbackReason,
        boolean cloud) {

    public String providerId() {
        return provider.id();
    }

    public String model() {
        return options.model();
    }
}
