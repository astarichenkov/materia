package com.lectureprocessor.pipeline.stage;

import java.nio.file.Files;

import org.springframework.stereotype.Component;

import com.lectureprocessor.media.MediaArtifact;
import com.lectureprocessor.pipeline.ArtifactManifest;
import com.lectureprocessor.pipeline.Fingerprints;
import com.lectureprocessor.pipeline.JobContext;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.pipeline.StageExecutor;
import com.lectureprocessor.pipeline.StageResult;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.thumbnail.ThumbnailService;
import com.lectureprocessor.video.FfmpegService;

/**
 * Validates the media artifact with FFprobe and records its duration. Rejects
 * empty/corrupt files so a broken download never reaches Whisper.
 */
@Component
public class MediaValidationStage implements StageExecutor {

    private final FfmpegService ffmpeg;
    private final LectureRepository lectureRepository;
    private final ThumbnailService thumbnails;

    public MediaValidationStage(FfmpegService ffmpeg, LectureRepository lectureRepository,
            ThumbnailService thumbnails) {
        this.ffmpeg = ffmpeg;
        this.lectureRepository = lectureRepository;
        this.thumbnails = thumbnails;
    }

    @Override
    public PipelineStage id() {
        return PipelineStage.MEDIA_VALIDATION;
    }

    @Override
    public String inputFingerprint(JobContext context) {
        MediaArtifact artifact = context.artifact();
        return Fingerprints.of("media-validation", Fingerprints.mediaKey(artifact == null ? null : artifact.path()));
    }

    @Override
    public StageResult execute(JobContext context) {
        long start = System.currentTimeMillis();
        MediaArtifact artifact = context.artifact();
        if (artifact == null || artifact.path() == null || !Files.isRegularFile(artifact.path())) {
            return StageResult.failed(id(), "Медиафайл недоступен для проверки", elapsed(start));
        }
        if (!artifact.exists()) {
            return StageResult.failed(id(), "Медиафайл пуст или повреждён", elapsed(start));
        }
        long durationMs = ffmpeg.probeDurationMs(artifact.path());
        if (durationMs > 0) {
            lectureRepository.updateDuration(context.lectureId(), durationMs);
            context.setArtifact(artifact.withDuration(durationMs));
            if (context.lecture() != null) {
                context.setLecture(context.lecture().withDuration(durationMs));
            }
        } else {
            context.addWarning("FFprobe не смог определить длительность медиафайла");
        }
        // Poster generation is best-effort and never fails media validation.
        thumbnails.generateQuietly(context.lectureId());
        return StageResult.completed(id(), ArtifactManifest.empty(), elapsed(start));
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
