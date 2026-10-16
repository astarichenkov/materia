package com.lectureprocessor.llm;

/**
 * Resolves the concrete provider and options for a logical task. Implemented by
 * {@link LlmTaskSettingsService}; a small interface so services and tests can
 * depend on resolution without the persistence machinery.
 */
public interface LlmTaskResolver {

    ResolvedLlmTask resolve(LlmTaskType task);
}
