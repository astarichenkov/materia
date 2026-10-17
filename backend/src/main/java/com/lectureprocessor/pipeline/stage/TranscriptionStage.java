package com.lectureprocessor.pipeline.stage;

import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.multilingual.LanguageDetector;
import com.lectureprocessor.multilingual.LanguageMode;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageRepository;
import com.lectureprocessor.multilingual.MultilingualProperties;
import com.lectureprocessor.pipeline.ArtifactManifest;
import com.lectureprocessor.pipeline.Fingerprints;
import com.lectureprocessor.pipeline.HeavyStageLimiter;
import com.lectureprocessor.pipeline.JobContext;
import com.lectureprocessor.pipeline.PipelineDataLoader;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.pipeline.StageExecutor;
import com.lectureprocessor.pipeline.StageResult;
import com.lectureprocessor.repository.SegmentRepository;
import com.lectureprocessor.repository.TimingRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.transcription.TranscriptionService;

/**
 * Runs whisper.cpp with the per-lecture language preference. The checkpoint
 * fingerprint includes the audio key, model path, resolved whisper language and
 * sanitizer version, so changing the language forces new transcription and
 * everything downstream — but an LLM/translation failure never re-runs Whisper.
 *
 * <p>Language metadata is taken from the JSON whisper.cpp already produced; a
 * cheap script heuristic is used only when the tool reports nothing.</p>
 */
@Component
public class TranscriptionStage implements StageExecutor {

    public static final String SANITIZER_VERSION = "v1";

    private final TranscriptionService transcription;
    private final SegmentRepository segmentRepository;
    private final FileStorageService storage;
    private final TimingRepository timings;
    private final HeavyStageLimiter limiter;
    private final AppProperties properties;
    private final PipelineDataLoader loader;
    private final LectureLanguageRepository languageRepository;
    private final LanguageDetector languageDetector;
    private final MultilingualProperties multilingual;

    public TranscriptionStage(TranscriptionService transcription, SegmentRepository segmentRepository,
            FileStorageService storage, TimingRepository timings, HeavyStageLimiter limiter,
            AppProperties properties, PipelineDataLoader loader,
            LectureLanguageRepository languageRepository, LanguageDetector languageDetector,
            MultilingualProperties multilingual) {
        this.transcription = transcription;
        this.segmentRepository = segmentRepository;
        this.storage = storage;
        this.timings = timings;
        this.limiter = limiter;
        this.properties = properties;
        this.loader = loader;
        this.languageRepository = languageRepository;
        this.languageDetector = languageDetector;
        this.multilingual = multilingual;
    }

    @Override
    public PipelineStage id() {
        return PipelineStage.TRANSCRIPTION;
    }

    @Override
    public String inputFingerprint(JobContext context) {
        Path audio = storage.resolve(context.lectureId(), PipelineDataLoader.AUDIO);
        LectureLanguage language = languageRepository.findOrDefault(context.lectureId(), multilingual);
        return Fingerprints.of("transcription", Fingerprints.mediaKey(audio),
                String.valueOf(properties.tools().whisperModelPath()),
                whisperLanguage(language), SANITIZER_VERSION);
    }

    @Override
    public StageResult execute(JobContext context) {
        long start = System.currentTimeMillis();
        Path audio = storage.resolve(context.lectureId(), PipelineDataLoader.AUDIO);
        LectureLanguage language = languageRepository.findOrDefault(context.lectureId(), multilingual);
        String whisperLanguage = whisperLanguage(language);
        Transcript transcript = limiter.run(context.cancellation(),
                () -> transcription.transcribe(audio, whisperLanguage));
        if (transcript == null || transcript.isEmpty()) {
            return StageResult.failed(id(), "Транскрипция пустая — в видео не распознана речь",
                    System.currentTimeMillis() - start);
        }
        context.cancellation().throwIfCancelled();
        LanguageDetector.Detection detection = languageDetector.detect(transcript.rawJson(),
                transcript, language.languageMode());
        LectureLanguage updated = language.withDetection(detection.language(),
                detection.confidence(), detection.source());
        languageRepository.save(updated);

        segmentRepository.replaceAll(context.lectureId(), transcript.segments());
        loader.writeString(context.lectureId(), PipelineDataLoader.TRANSCRIPT_JSON, transcript.rawJson());
        context.setTranscript(transcript);
        timings.record(context.lectureId(), TimingRepository.WHISPER, System.currentTimeMillis() - start);
        return StageResult.completed(id(),
                ArtifactManifest.of(
                        ArtifactManifest.required("transcript", PipelineDataLoader.TRANSCRIPT_JSON)),
                System.currentTimeMillis() - start);
    }

    private static String whisperLanguage(LectureLanguage language) {
        // AUTO must let whisper.cpp detect the language instead of forcing ru.
        return language.languageMode() == LanguageMode.AUTO ? "auto"
                : language.languageMode().whisperCode();
    }
}
