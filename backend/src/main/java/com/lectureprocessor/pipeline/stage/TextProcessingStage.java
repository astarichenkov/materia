package com.lectureprocessor.pipeline.stage;

import java.util.List;

import org.springframework.stereotype.Component;

import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.llm.LlmException;
import com.lectureprocessor.llm.LlmOptions;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmSettingsService;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageRepository;
import com.lectureprocessor.multilingual.MultilingualProperties;
import com.lectureprocessor.multilingual.TranslationRepository;
import com.lectureprocessor.multilingual.TranslationSegment;
import com.lectureprocessor.multilingual.TranslationStatus;
import com.lectureprocessor.processing.OutputVariantService;
import com.lectureprocessor.processing.ProcessingProfile;
import com.lectureprocessor.processing.ProcessingProfileService;
import com.lectureprocessor.scenario.ProcessingScenario;
import com.lectureprocessor.scenario.ScenarioCatalog;
import com.lectureprocessor.scenario.ScenarioOutputValidator;
import com.lectureprocessor.scenario.TranscriptDocumentBuilder;
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
import com.lectureprocessor.pipeline.JobProgressRegistry;
import com.lectureprocessor.pipeline.JobState;
import com.lectureprocessor.repository.PipelineRunRepository;
import com.lectureprocessor.text.ChunkProgressListener;
import com.lectureprocessor.text.PromptTemplates;
import com.lectureprocessor.text.TextQualityReport;
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
    private final TranslationRepository translations;
    private final LectureLanguageRepository languageRepository;
    private final MultilingualProperties multilingual;
    private final ProcessingProfileService profiles;
    private final OutputVariantService variants;
    private final JobProgressRegistry progressRegistry;
    private final PipelineRunRepository runs;
    private final ScenarioCatalog scenarios;
    private final ScenarioOutputValidator validator;
    private final TranscriptDocumentBuilder transcriptDocuments;

    public TextProcessingStage(TextProcessingService textProcessing, LlmTaskResolver taskResolver,
            LlmSettingsService llmSettings, FileStorageService storage, TimingRepository timings,
            HeavyStageLimiter limiter, PipelineDataLoader loader, TranslationRepository translations,
            LectureLanguageRepository languageRepository, MultilingualProperties multilingual,
            ProcessingProfileService profiles, OutputVariantService variants,
            JobProgressRegistry progressRegistry, PipelineRunRepository runs,
            ScenarioCatalog scenarios, ScenarioOutputValidator validator,
            TranscriptDocumentBuilder transcriptDocuments) {
        this.textProcessing = textProcessing;
        this.taskResolver = taskResolver;
        this.llmSettings = llmSettings;
        this.storage = storage;
        this.timings = timings;
        this.limiter = limiter;
        this.loader = loader;
        this.translations = translations;
        this.languageRepository = languageRepository;
        this.multilingual = multilingual;
        this.profiles = profiles;
        this.variants = variants;
        this.progressRegistry = progressRegistry;
        this.runs = runs;
        this.scenarios = scenarios;
        this.validator = validator;
        this.transcriptDocuments = transcriptDocuments;
    }

    @Override
    public PipelineStage id() {
        return PipelineStage.TEXT_PROCESSING;
    }

    @Override
    public String inputFingerprint(JobContext context) {
        // The fingerprint must describe the configuration that will actually be
        // used, not the global default: the task may resolve to another provider
        // or model. Preview and execution therefore share this exact code path.
        ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.LECTURE_PROCESSING);
        LlmRunConfig config = resolved.config();
        String transcriptKey = Fingerprints.mediaKey(
                storage.resolve(context.lectureId(), PipelineDataLoader.TRANSCRIPT_JSON));
        String translationKey = translations.findLatest(context.lectureId())
                .map(t -> t.id() + ":" + t.status() + ":" + t.segmentsTranslated())
                .orElse("none");
        ProcessingProfile profile = context.profileOverride() != null
                ? context.profileOverride() : profiles.get(context.lectureId());
        return Fingerprints.of("text-processing", transcriptKey, translationKey,
                String.valueOf(resolved.providerId()),
                String.valueOf(config.provider()),
                String.valueOf(config.model()), String.valueOf(config.temperature()),
                String.valueOf(config.maxTokens()), String.valueOf(config.contextWindow()),
                String.valueOf(config.thinking()), String.valueOf(config.promptTemplate()),
                String.valueOf(context.lecture() != null && context.lecture().cleanTranscript()),
                profile.fingerprintPart());
    }

    @Override
    public StageResult execute(JobContext context) {
        long start = System.currentTimeMillis();
        Transcript transcript = context.transcript();
        if (transcript == null) {
            transcript = loader.transcript(context.lectureId());
        }
        if (transcript == null || transcript.isEmpty()) {
            return StageResult.failed(id(), "Транскрипция недоступна для обработки текста",
                    System.currentTimeMillis() - start);
        }
        ProcessingProfile profile = context.profileOverride() != null
                ? context.profileOverride() : profiles.get(context.lectureId());
        ProcessingScenario scenario = profile.scenario();
        final Transcript source = translatedTranscript(context, transcript);

        String cleaned = null;
        StructuredNotes notes;
        TextQualityReport quality = TextQualityReport.empty();
        ChunkProgressListener progress = chunkProgress(context);

        if (scenario != null && !scenario.usesLlm()) {
            // Verbatim / translation-only: no LLM call at all. The document is
            // assembled from the stored segments so nothing can be paraphrased,
            // reordered or dropped by a model.
            boolean translated = scenario == ProcessingScenario.TRANSLATION
                    && translationUsable(context);
            TranscriptDocumentBuilder.Result result = transcriptDocuments.build(scenario,
                    source.segments(), translated);
            notes = result.notes();
            quality = quality.withWarnings(result.warnings());
            loader.writeNotes(context.lectureId(), notes);
            loader.writeQuality(context.lectureId(), quality);
            variants.archive(context.lectureId(), profile, null, notes.markdown());
            context.setNotes(notes);
            context.setTextProcessed(true);
            timings.record(context.lectureId(), TimingRepository.LLM,
                    System.currentTimeMillis() - start);
            return StageResult.completed(id(),
                    ArtifactManifest.of(
                            ArtifactManifest.required("notes", PipelineDataLoader.NOTES_JSON),
                            ArtifactManifest.optional("quality",
                                    PipelineDataLoader.NOTES_QUALITY_JSON)),
                    System.currentTimeMillis() - start, quality.warnings());
        }

        ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.LECTURE_PROCESSING);
        if (!resolved.provider().isAvailable()) {
            return StageResult.failed(id(),
                    "LLM provider недоступен: " + resolved.providerId()
                            + ". Для локального режима запустите Ollama и загрузите модель "
                            + "(OLLAMA_BASE_URL / OLLAMA_CHAT_MODEL); для облака добавьте API key.",
                    System.currentTimeMillis() - start);
        }
        // Snapshot the resolved configuration so a running job is never affected
        // by later settings changes and its checkpoint stays reproducible.
        LlmRunConfig config = resolved.config();
        context.setLlmConfig(config);
        LlmOptions options = resolved.options();
        String notesPrompt = profile.resolvedPrompt();
        boolean clean = context.lecture() != null && context.lecture().cleanTranscript();
        String documentTitle = profile.documentLabel();

        if (profile.outputFormat().cleanTranscript()) {
            final String prompt = notesPrompt;
            TextProcessingService.CleanedTranscriptDocument document = limiter.run(
                    context.cancellation(),
                    () -> textProcessing.cleanTranscriptDocument(resolved.provider(),
                            source.segments(), options, prompt, documentTitle, progress));
            cleaned = document.cleanedText();
            notes = document.notes();
        } else {
            final String instructions = profile.instructions();
            final String contract = profile.outputFormat().structureContract();
            final com.lectureprocessor.text.AssemblyPolicy policy = scenario == null
                    ? com.lectureprocessor.text.AssemblyPolicy.SEMANTIC : scenario.assemblyPolicy();
            final boolean includeSummary = scenario == null || scenario.includeSummary();
            cleaned = limiter.run(context.cancellation(),
                    () -> clean ? textProcessing.cleanTranscript(resolved.provider(),
                            source.segments(), options)
                            : null);
            context.cancellation().throwIfCancelled();
            TextProcessingService.GeneratedNotes generated = limiter.run(context.cancellation(),
                    () -> textProcessing.generateStructuredNotes(resolved.provider(),
                            source.segments(), options, notesPrompt, instructions, contract,
                            policy, documentTitle, includeSummary, progress));
            notes = generated.notes();
            quality = generated.quality();
        }
        context.cancellation().throwIfCancelled();

        // Scenario-specific validation: warnings are persisted, an empty result
        // is already a hard failure inside the generation call.
        quality = quality.withWarnings(validator.validate(scenario, notes.markdown(),
                source.segments()));

        loader.writeNotes(context.lectureId(), notes);
        loader.writeQuality(context.lectureId(), quality);
        if (cleaned != null) {
            loader.writeString(context.lectureId(), PipelineDataLoader.CLEANED_TXT, cleaned);
        }
        // Archive the produced variant before any later stage can overwrite the
        // working notes.md / notes.docx.
        variants.archive(context.lectureId(), profile, config, notes.markdown());
        context.setNotes(notes);
        context.setTextProcessed(true);
        timings.record(context.lectureId(), TimingRepository.LLM, System.currentTimeMillis() - start);
        return StageResult.completed(id(),
                ArtifactManifest.of(
                        ArtifactManifest.required("notes", PipelineDataLoader.NOTES_JSON),
                        ArtifactManifest.optional("cleaned", PipelineDataLoader.CLEANED_TXT),
                        ArtifactManifest.optional("quality", PipelineDataLoader.NOTES_QUALITY_JSON)),
                System.currentTimeMillis() - start, quality.warnings());
    }

    /**
     * Reports real chunk progress into the current run. Only the count is
     * reported — no invented percentage or ETA.
     */
    private ChunkProgressListener chunkProgress(JobContext context) {
        return (completed, total) -> {
            String runId = context.runId();
            if (runId == null) {
                return;
            }
            String message = "Обработка текста: " + completed + " из " + total + " фрагментов";
            runs.currentStage(runId, PipelineStage.TEXT_PROCESSING, completed, total, message);
            progressRegistry.update(context.lectureId(), p -> p == null ? null
                    : p.withRun(runId, JobState.RUNNING, completed, total, message));
        };
    }

    /**
     * Uses the Russian translation for note generation when it exists and the
     * lecture is configured for translation. Missing segments fall back to the
     * original text, so a partial translation never loses content.
     */
    private boolean translationUsable(JobContext context) {
        return translations.findLatest(context.lectureId())
                .filter(t -> "ru".equals(t.targetLanguage()))
                .filter(t -> t.status() == TranslationStatus.COMPLETED
                        || t.status() == TranslationStatus.PARTIAL)
                .filter(t -> t.segmentsTranslated() > 0)
                .isPresent();
    }

    private Transcript translatedTranscript(JobContext context, Transcript original) {
        LectureLanguage language = languageRepository.findOrDefault(context.lectureId(), multilingual);
        if (!language.resolvedTranslationMode().wantsTranslation()) {
            return original;
        }
        return translations.findLatest(context.lectureId())
                .filter(t -> "ru".equals(t.targetLanguage()))
                .filter(t -> t.status() == TranslationStatus.COMPLETED
                        || t.status() == TranslationStatus.PARTIAL)
                .map(t -> {
                    List<TranslationSegment> parts = translations.segments(t.id());
                    if (parts.isEmpty()) {
                        return original;
                    }
                    List<TranscriptSegment> segments = parts.stream()
                            .map(TranslationSegment::toTranscriptSegment).toList();
                    return new Transcript(segments, original.rawJson());
                })
                .orElse(original);
    }
}
