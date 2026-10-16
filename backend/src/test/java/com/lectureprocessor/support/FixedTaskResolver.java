package com.lectureprocessor.support;

import java.util.EnumMap;
import java.util.Map;

import com.lectureprocessor.llm.LlmOptions;
import com.lectureprocessor.llm.LlmProvider;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.text.PromptTemplates;

/**
 * Test {@link LlmTaskResolver} that returns a fixed provider for every task.
 * Lets services that depend on per-task routing be unit-tested without any
 * persistence or Spring context.
 */
public class FixedTaskResolver implements LlmTaskResolver {

    private final Map<LlmTaskType, ResolvedLlmTask> resolved = new EnumMap<>(LlmTaskType.class);

    public FixedTaskResolver(LlmProvider provider) {
        this(provider, LlmOptions.of(provider.defaultChatModel()));
    }

    public FixedTaskResolver(LlmProvider provider, LlmOptions options) {
        LlmRunConfig config = new LlmRunConfig(provider.id(), options.model(), options.temperature(),
                options.maxTokens(), options.numCtx(), options.thinking(), PromptTemplates.STRUCTURED_NOTES);
        ResolvedLlmTask task = new ResolvedLlmTask(null, config, provider, options, false, null, provider.cloud());
        for (LlmTaskType type : LlmTaskType.values()) {
            resolved.put(type, new ResolvedLlmTask(type, config, provider, options, false, null, provider.cloud()));
        }
    }

    @Override
    public ResolvedLlmTask resolve(LlmTaskType task) {
        ResolvedLlmTask taskResult = resolved.get(task);
        if (taskResult != null) {
            return taskResult;
        }
        // Fallback to any registered task (keeps simple tests terse).
        return resolved.values().iterator().next();
    }
}
