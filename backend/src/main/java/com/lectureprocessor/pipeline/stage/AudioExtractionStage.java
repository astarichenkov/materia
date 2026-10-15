package com.lectureprocessor.pipeline.stage;

import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.lectureprocessor.pipeline.ArtifactManifest;
import com.lectureprocessor.pipeline.Fingerprints;
import com.lectureprocessor.pipeline.JobContext;
import com.lectureprocessor.pipeline.PipelineDataLoader;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.pipeline.StageExecutor;
import com.lectureprocessor.pipeline.StageResult;
import com.lectureprocessor.repository.TimingRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.video.FfmpegService;

/**
 * Extracts a mono 16 kHz WAV suitable for whisper.cpp.
 */
@Component
public class AudioExtractionStage implements StageExecutor {

    private final FfmpegService ffmpeg;
    private final FileStorageService storage;
    private final TimingRepository timings;

    public AudioExtractionStage(FfmpegService ffmpeg, FileStorageService storage, TimingRepository timings) {
        this.ffmpeg = ffmpeg;
        this.storage = storage;
        this.timings = timings;
    }

    @Override
    public PipelineStage id() {
        return PipelineStage.AUDIO_EXTRACTION;
    }

    @Override
    public String inputFingerprint(JobContext context) {
        return Fingerprints.of("audio-extraction",
                Fingerprints.mediaKey(context.artifact() == null ? null : context.artifact().path()),
                "wav-16k-mono-v1");
    }

    @Override
    public StageResult execute(JobContext context) {
        long start = System.currentTimeMillis();
        Path source = context.artifact().path();
        Path audio = storage.resolve(context.lectureId(), PipelineDataLoader.AUDIO);
        ffmpeg.extractAudio(source, audio);
        timings.record(context.lectureId(), TimingRepository.FFMPEG, System.currentTimeMillis() - start);
        return StageResult.completed(id(),
                ArtifactManifest.of(ArtifactManifest.required("audio", PipelineDataLoader.AUDIO)),
                System.currentTimeMillis() - start);
    }
}
