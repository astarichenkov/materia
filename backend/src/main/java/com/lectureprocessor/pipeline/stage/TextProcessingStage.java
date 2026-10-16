package com.lectureprocessor.pipeline.stage;

import org.springframework.stereotype.Component;

import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.llm.LlmException;
import com.lectureprocessor.llm.LlmOptions;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmSettingsService;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.pipeline.ArtifactManifest;
import com.lectureprocessor.pipeline.Fingerprints;
import com.lectureprocessor.pipeline.HeavyStageLimiter;
import com.lectureprocessor.pipeline.JobContext;
import com.lectureprocessor.pipeline.PipelineDataLoader;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.pipeline.StageExecutor;
import com.lectureprocessor.pipeline.StageResult;
import com.lectureprocessor.repository.TimingRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.text.PromptTemplates;
import com.lectureprocessor.text.StructuredNotes;
import com.lectureprocessor.text.TextProcessingService;

/**
 * LLM text processing: optional transcript cleanup and structured notes. The
 * fingerprint covers the transcript key and the full LLM configuration, so a
 * model/prompt change re-runs text processing (and downstream export) but keeps
 * the expensive transcript.
 */
@Component
public class TextProcessingStage implements StageExecutor {

    private final TextProcessingService textProcessing;
    private final LlmTaskResolver taskResolver;
    private final LlmSettingsService llmSettings;
    private final FileStorageService storage;
    private final TimingRepository timings;
    private final HeavyStageLimiter limiter;
    private final PipelineDataLoader loader;

    public TextProcessingStage(TextProcessingService textProcessing, LlmTaskResolver taskResolver,
            LlmSettingsService llmSettings, FileStorageService storage, TimingRepository timings,
            HeavyStageLimiter limiter, PipelineDataLoader loader) {
        this.textProcessing = textProcessing;
        this.taskResolver = taskResolver;
        this.llmSettings = llmSettings;
        this.storage = storage;
        this.timings = timings;
        this.limiter = limiter;
        this.loader = loader;
    }

    @Override
    public PipelineStage id() {
        return PipelineStage.TEXT_PROCESSING;
    }

    @Override
    public String inputFingerprint(JobContext context) {
        LlmRunConfig config = llmSettings.current();
        String transcriptKey = Fingerprints.mediaKey(
                storage.resolve(context.lectureId(), PipelineDataLoader.TRANSCRIPT_JSON));
        return Fingerprints.of("text-processing", transcriptKey, String.valueOf(config.provider()),
                String.valueOf(config.model()), String.valueOf(config.temperature()),
                String.valueOf(config.maxTokens()), String.valueOf(config.contextWindow()),
                String.valueOf(config.thinking()), String.valueOf(config.promptTemplate()),
                String.valueOf(context.lecture() != null && context.lecture().cleanTranscript()));
    }

    @Override
    public StageResult execute(JobContext context) {
        long start = System.currentTimeMillis();
        ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.LECTURE_PROCESSING);
        if (!resolved.provider().isAvailable()) {
            return StageResult.failed(id(),
                    "LLM provider недоступен: " + resolved.providerId()
                            + ". Для локального режима запустите Ollama и загрузите модель "
                            + "(OLLAMA_BASE_URL / OLLAMA_CHAT_MODEL); для облака добавьте API key.",
                    System.currentTimeMillis() - start);
        }
        Transcript transcript = context.transcript();
        if (transcript == null) {
            transcript = loader.transcript(context.lectureId());
        }
        if (transcript == null || transcript.isEmpty()) {
            return StageResult.failed(id(), "Транскрипция недоступна для обработки текста",
                    System.currentTimeMillis() - start);
        }
        // Snapshot the resolved configuration so a running job is never affected
        // by later settings changes and its checkpoint stays reproducible.
        LlmRunConfig config = resolved.config();
        context.setLlmConfig(config);
        LlmOptions options = resolved.options();
        String notesPrompt = config.promptTemplate() == null
                ? PromptTemplates.STRUCTURED_NOTES : config.promptTemplate();
        boolean clean = context.lecture() != null && context.lecture().cleanTranscript();
        final Transcript source = transcript;

        String cleaned = limiter.run(context.cancellation(),
                () -> clean ? textProcessing.cleanTranscript(resolved.provider(), source.segments(), options)
                        : null);
        context.cancellation().throwIfCancelled();
        StructuredNotes notes = limiter.run(context.cancellation(),
                () -> textProcessing.generateStructuredNotes(resolved.provider(), source.segments(), options,
                        notesPrompt));
        context.cancellation().throwIfCancelled();

        loader.writeNotes(context.lectureId(), notes);
        if (cleaned != null) {
            loader.writeString(context.lectureId(), PipelineDataLoader.CLEANED_TXT, cleaned);
        }
        context.setNotes(notes);
        context.setTextProcessed(true);
        timings.record(context.lectureId(), TimingRepository.LLM, System.currentTimeMillis() - start);
        return StageResult.completed(id(),
                ArtifactManifest.of(
                        ArtifactManifest.required("notes", PipelineDataLoader.NOTES_JSON),
                        ArtifactManifest.optional("cleaned", PipelineDataLoader.CLEANED_TXT)),
                System.currentTimeMillis() - start);
    }
}
