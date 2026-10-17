package com.lectureprocessor.pipeline.stage;

import java.util.List;

import org.springframework.stereotype.Component;

import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.multilingual.LanguageDetector;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageRepository;
import com.lectureprocessor.multilingual.MultilingualProperties;
import com.lectureprocessor.multilingual.TranslationGlossaryService;
import com.lectureprocessor.multilingual.TranslationMode;
import com.lectureprocessor.multilingual.TranslationService;
import com.lectureprocessor.multilingual.TranslationStatus;
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

import tools.jackson.databind.ObjectMapper;

/**
 * Optional, resumable translation stage between transcription and LLM
 * formatting. The original transcript artifact is never touched: the outcome is
 * stored in its own table and {@code translation.json}.
 *
 * <p>Returns FAILED (treated as a warning by the coordinator because the stage
 * is optional) for partial translations so a resume retries only the missing
 * segments. All other stages continue, producing documents in the source
 * language when translation is unavailable.</p>
 */
@Component
public class TranslationStage implements StageExecutor {

    private final TranslationService translationService;
    private final LectureLanguageRepository languageRepository;
    private final LanguageDetector languageDetector;
    private final TranslationGlossaryService glossary;
    private final LlmTaskResolver taskResolver;
    private final MultilingualProperties properties;
    private final FileStorageService storage;
    private final TimingRepository timings;
    private final HeavyStageLimiter limiter;
    private final PipelineDataLoader loader;
    private final ObjectMapper objectMapper;

    public TranslationStage(TranslationService translationService,
            LectureLanguageRepository languageRepository, LanguageDetector languageDetector,
            TranslationGlossaryService glossary, LlmTaskResolver taskResolver,
            MultilingualProperties properties, FileStorageService storage,
            TimingRepository timings, HeavyStageLimiter limiter, PipelineDataLoader loader,
            ObjectMapper objectMapper) {
        this.translationService = translationService;
        this.languageRepository = languageRepository;
        this.languageDetector = languageDetector;
        this.glossary = glossary;
        this.taskResolver = taskResolver;
        this.properties = properties;
        this.storage = storage;
        this.timings = timings;
        this.limiter = limiter;
        this.loader = loader;
        this.objectMapper = objectMapper;
    }

    @Override
    public PipelineStage id() {
        return PipelineStage.TRANSLATION;
    }

    @Override
    public boolean optional() {
        return true;
    }

    @Override
    public String inputFingerprint(JobContext context) {
        LectureLanguage language = resolvedLanguage(context);
        TranslationMode mode = language.resolvedTranslationMode();
        if (!properties.enabled() || !mode.wantsTranslation()) {
            return Fingerprints.of("translation", "skipped", language.normalizedSource());
        }
        ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.LECTURE_TRANSLATION);
        String transcriptKey = Fingerprints.mediaKey(
                storage.resolve(context.lectureId(), PipelineDataLoader.TRANSCRIPT_JSON));
        return Fingerprints.of("translation", transcriptKey, language.normalizedSource(),
                language.targetLanguage(), mode.name(), resolved.providerId(),
                resolved.config().model(), String.valueOf(resolved.config().temperature()),
                String.valueOf(resolved.config().maxTokens()),
                String.valueOf(resolved.config().contextWindow()),
                String.valueOf(resolved.config().thinking()), properties.promptVersion(),
                String.valueOf(properties.maxBatchChars()),
                String.valueOf(properties.maxSegmentsPerBatch()),
                glossary.version(language.normalizedSource(), language.targetLanguage()));
    }

    @Override
    public StageResult execute(JobContext context) {
        long start = System.currentTimeMillis();
        LectureLanguage language = resolvedLanguage(context);
        TranslationMode mode = language.resolvedTranslationMode();
        if (!properties.enabled() || !mode.wantsTranslation()) {
            return StageResult.completed(id(), ArtifactManifest.empty(),
                    System.currentTimeMillis() - start);
        }
        Transcript transcript = context.transcript();
        if (transcript == null) {
            transcript = loader.transcript(context.lectureId());
        }
        if (transcript == null || transcript.isEmpty()) {
            return StageResult.failed(id(), "Транскрипция недоступна для перевода",
                    System.currentTimeMillis() - start);
        }
        ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.LECTURE_TRANSLATION);
        if (!resolved.provider().isAvailable()) {
            return StageResult.failed(id(), "Провайдер перевода недоступен: " + resolved.providerId()
                    + ". Настройте ключ DeepSeek или локальную модель Ollama в /settings.",
                    System.currentTimeMillis() - start);
        }
        final Transcript source = transcript;
        TranslationService.TranslationOutcome outcome = limiter.run(context.cancellation(),
                () -> translationService.translate(new TranslationService.TranslationRequest(
                        context.lectureId(), 0L, source.segments(),
                        TranslationService.sourceFingerprint(source.segments()),
                        language.normalizedSource(), language.targetLanguage(), resolved,
                        context.cancellation())));
        loader.writeString(context.lectureId(), PipelineDataLoader.TRANSLATION_JSON,
                objectMapper.writeValueAsString(outcome));
        timings.record(context.lectureId(), TimingRepository.LLM,
                System.currentTimeMillis() - start);

        ArtifactManifest manifest = ArtifactManifest.of(
                ArtifactManifest.required("translation", PipelineDataLoader.TRANSLATION_JSON));
        if (outcome.status() == TranslationStatus.COMPLETED) {
            return new StageResult(id(), com.lectureprocessor.pipeline.StageState.COMPLETED,
                    manifest, null, System.currentTimeMillis() - start, outcome.warnings());
        }
        String error = outcome.status() == TranslationStatus.FAILED
                ? "Перевод не выполнен: " + outcome.warnings().stream().findFirst().orElse("нет сегментов")
                : "Перевод частичный: " + outcome.translated() + "/" + outcome.total()
                        + " сегментов. Повторите resume, чтобы перевести оставшиеся.";
        return StageResult.failed(id(), error, System.currentTimeMillis() - start);
    }

    private LectureLanguage resolvedLanguage(JobContext context) {
        LectureLanguage language = languageRepository.findOrDefault(context.lectureId(), properties);
        if (language.detectedLanguage() == null) {
            Transcript transcript = context.transcript();
            if (transcript == null) {
                transcript = loader.transcript(context.lectureId());
            }
            if (transcript != null) {
                LanguageDetector.Detection detection = languageDetector.detect(transcript.rawJson(),
                        transcript, language.languageMode());
                language = language.withDetection(detection.language(), detection.confidence(),
                        detection.source());
                languageRepository.save(language);
            }
        }
        return language;
    }
}
