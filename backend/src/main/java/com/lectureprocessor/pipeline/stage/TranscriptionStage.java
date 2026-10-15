package com.lectureprocessor.pipeline.stage;

import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.domain.Transcript;
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
 * Runs whisper.cpp. The checkpoint fingerprint includes the audio key, the model
 * path and the language, so changing any of them invalidates transcription and
 * everything downstream — but an LLM failure alone never re-runs Whisper.
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

    public TranscriptionStage(TranscriptionService transcription, SegmentRepository segmentRepository,
            FileStorageService storage, TimingRepository timings, HeavyStageLimiter limiter,
            AppProperties properties, PipelineDataLoader loader) {
        this.transcription = transcription;
        this.segmentRepository = segmentRepository;
        this.storage = storage;
        this.timings = timings;
        this.limiter = limiter;
        this.properties = properties;
        this.loader = loader;
    }

    @Override
    public PipelineStage id() {
        return PipelineStage.TRANSCRIPTION;
    }

    @Override
    public String inputFingerprint(JobContext context) {
        Path audio = storage.resolve(context.lectureId(), PipelineDataLoader.AUDIO);
        return Fingerprints.of("transcription", Fingerprints.mediaKey(audio),
                String.valueOf(properties.tools().whisperModelPath()),
                String.valueOf(properties.tools().whisperLanguage()), SANITIZER_VERSION);
    }

    @Override
    public StageResult execute(JobContext context) {
        long start = System.currentTimeMillis();
        Path audio = storage.resolve(context.lectureId(), PipelineDataLoader.AUDIO);
        Transcript transcript = limiter.run(context.cancellation(), () -> transcription.transcribe(audio));
        if (transcript == null || transcript.isEmpty()) {
            return StageResult.failed(id(), "Транскрипция пустая — в видео не распознана речь",
                    System.currentTimeMillis() - start);
        }
        context.cancellation().throwIfCancelled();
        segmentRepository.replaceAll(context.lectureId(), transcript.segments());
        loader.writeString(context.lectureId(), PipelineDataLoader.TRANSCRIPT_JSON, transcript.rawJson());
        context.setTranscript(transcript);
        timings.record(context.lectureId(), TimingRepository.WHISPER, System.currentTimeMillis() - start);
        return StageResult.completed(id(),
                ArtifactManifest.of(
                        ArtifactManifest.required("transcript", PipelineDataLoader.TRANSCRIPT_JSON)),
                System.currentTimeMillis() - start);
    }
}
