package com.lectureprocessor.pipeline.stage;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.lectureprocessor.download.VideoMetadataService;
import com.lectureprocessor.download.VideoPlatform;
import com.lectureprocessor.media.MediaArtifact;
import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.media.MediaSourceKind;
import com.lectureprocessor.pipeline.ArtifactManifest;
import com.lectureprocessor.pipeline.Fingerprints;
import com.lectureprocessor.pipeline.JobContext;
import com.lectureprocessor.pipeline.PipelineDataLoader;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.pipeline.StageExecutor;
import com.lectureprocessor.pipeline.StageResult;
import com.lectureprocessor.repository.MediaSourceRepository;
import com.lectureprocessor.storage.FileStorageService;

/**
 * Resolves the persisted {@code media_source} into a {@link MediaArtifact}.
 * Local files are validated to exist; remote URLs are validated (allow-list +
 * SSRF) and their metadata is loaded/probed. No download happens here.
 */
@Component
public class SourceResolutionStage implements StageExecutor {

    private final MediaSourceRepository mediaSourceRepository;
    private final FileStorageService storage;
    private final VideoMetadataService metadataService;
    private final PipelineDataLoader loader;

    public SourceResolutionStage(MediaSourceRepository mediaSourceRepository,
            FileStorageService storage, VideoMetadataService metadataService, PipelineDataLoader loader) {
        this.mediaSourceRepository = mediaSourceRepository;
        this.storage = storage;
        this.metadataService = metadataService;
        this.loader = loader;
    }

    @Override
    public PipelineStage id() {
        return PipelineStage.SOURCE_RESOLUTION;
    }

    @Override
    public String inputFingerprint(JobContext context) {
        MediaSource source = context.source();
        if (source != null) {
            return Fingerprints.of(source.kind().name(),
                    String.valueOf(source.originalUrl()), String.valueOf(source.originalFilename()),
                    String.valueOf(source.quality()), String.valueOf(source.audioOnly()));
        }
        // A locally uploaded lecture may not have a media_source row on its first
        // run (the row is created by this stage). The fingerprint must be the same
        // before and after that row exists, otherwise the first completed run is
        // invalidated and the whole downstream pipeline — including Whisper —
        // re-runs on every subsequent attempt.
        String filename = context.lecture() == null ? null : context.lecture().originalFilename();
        return Fingerprints.of(MediaSourceKind.LOCAL_FILE.name(), "null", String.valueOf(filename),
                "null", "false");
    }

    @Override
    public StageResult execute(JobContext context) {
        long start = System.currentTimeMillis();
        MediaSource source = mediaSourceRepository.findByLecture(context.lectureId())
                .orElseGet(() -> {
                    MediaSource legacy = MediaSource.local(context.lectureId(),
                            context.lecture().originalFilename());
                    mediaSourceRepository.save(legacy);
                    return legacy;
                });
        context.setSource(source);

        if (source.kind() == MediaSourceKind.REMOTE_URL) {
            if (source.title() == null && source.metadataJson() == null) {
                var metadata = metadataService.probe(source.originalUrl(), context.cancellation());
                context.metadata().setTitle(metadata.title());
                context.metadata().setProvider(metadata.provider() != null ? metadata.provider()
                        : VideoPlatform.fromUrl(source.originalUrl()).map(VideoPlatform::displayName)
                                .orElse(null));
                context.metadata().setExternalId(metadata.externalId());
                context.metadata().setUploader(metadata.uploader());
                context.metadata().setDurationMs(metadata.durationMs());
                context.metadata().setWidth(metadata.width());
                context.metadata().setHeight(metadata.height());
                context.metadata().setRawJson(metadata.rawJson());
            } else {
                copyStoredMetadata(source, context);
            }
            MediaArtifact pending = new MediaArtifact(context.lectureId(), null, "video/*", 0L,
                    context.metadata().durationMs(), MediaSourceKind.REMOTE_URL, source.originalUrl(),
                    null);
            context.setArtifact(pending);
            return StageResult.completed(id(), ArtifactManifest.empty(), elapsed(start));
        }

        Path path = storage.resolve(context.lectureId(), context.lecture().storedFilename());
        if (!Files.isRegularFile(path) || sizeOf(path) <= 0) {
            return StageResult.failed(id(), "Исходный файл не найден: " + context.lecture().storedFilename(),
                    elapsed(start));
        }
        long size = sizeOf(path);
        context.setArtifact(new MediaArtifact(context.lectureId(), path, context.lecture().contentType(),
                size, context.lecture().durationMs(), MediaSourceKind.LOCAL_FILE,
                context.lecture().originalFilename(), null));
        return StageResult.completed(id(),
                ArtifactManifest.of(ArtifactManifest.required("source", context.lecture().storedFilename())),
                elapsed(start));
    }

    private void copyStoredMetadata(MediaSource source, JobContext context) {
        context.metadata().setTitle(source.title());
        context.metadata().setProvider(source.provider());
        context.metadata().setExternalId(source.externalId());
        context.metadata().setUploader(source.uploader());
        context.metadata().setDurationMs(source.durationMs());
        context.metadata().setRawJson(source.metadataJson());
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
