package com.lectureprocessor.pipeline.stage;

import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.lectureprocessor.download.DownloadProgress;
import com.lectureprocessor.download.DownloadRequest;
import com.lectureprocessor.download.DownloadResult;
import com.lectureprocessor.download.DownloadState;
import com.lectureprocessor.download.MediaQuality;
import com.lectureprocessor.download.VideoDownloader;
import com.lectureprocessor.media.MediaArtifact;
import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.media.MediaSourceKind;
import com.lectureprocessor.pipeline.ArtifactManifest;
import com.lectureprocessor.pipeline.Fingerprints;
import com.lectureprocessor.pipeline.JobContext;
import com.lectureprocessor.pipeline.JobProgressRegistry;
import com.lectureprocessor.pipeline.PipelineDataLoader;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.pipeline.StageExecutor;
import com.lectureprocessor.pipeline.StageResult;
import com.lectureprocessor.repository.DownloadStateRepository;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.MediaSourceRepository;
import com.lectureprocessor.storage.FileStorageService;

/**
 * Downloads a remote URL into the lecture workspace. No-op (COMPLETED with an
 * empty manifest) for local uploads. Emits live progress to the registry and
 * persists a snapshot in {@code download_state}.
 */
@Component
public class DownloadingStage implements StageExecutor {

    private final VideoDownloader downloader;
    private final MediaSourceRepository mediaSourceRepository;
    private final DownloadStateRepository downloadStateRepository;
    private final LectureRepository lectureRepository;
    private final FileStorageService storage;
    private final JobProgressRegistry progressRegistry;
    private final PipelineDataLoader loader;

    public DownloadingStage(VideoDownloader downloader, MediaSourceRepository mediaSourceRepository,
            DownloadStateRepository downloadStateRepository, LectureRepository lectureRepository,
            FileStorageService storage, JobProgressRegistry progressRegistry,
            PipelineDataLoader loader) {
        this.downloader = downloader;
        this.mediaSourceRepository = mediaSourceRepository;
        this.downloadStateRepository = downloadStateRepository;
        this.lectureRepository = lectureRepository;
        this.storage = storage;
        this.progressRegistry = progressRegistry;
        this.loader = loader;
    }

    @Override
    public PipelineStage id() {
        return PipelineStage.DOWNLOADING;
    }

    @Override
    public String inputFingerprint(JobContext context) {
        MediaSource source = context.source();
        if (source == null || source.kind() != MediaSourceKind.REMOTE_URL) {
            return "local";
        }
        return Fingerprints.of("download", source.originalUrl(), source.quality(),
                String.valueOf(source.audioOnly()));
    }

    @Override
    public StageResult execute(JobContext context) {
        long start = System.currentTimeMillis();
        MediaSource source = context.source();
        if (source == null || source.kind() != MediaSourceKind.REMOTE_URL) {
            return StageResult.completed(id(), ArtifactManifest.empty(), elapsed(start));
        }
        MediaQuality quality = MediaQuality.fromLabel(source.quality());
        Path dir = storage.lectureDir(context.lectureId());
        downloadStateRepository.save(DownloadState.pending(context.lectureId()));

        DownloadResult result = downloader.download(
                new DownloadRequest(context.lectureId(), source.originalUrl(), quality,
                        source.audioOnly(), dir, null),
                context.cancellation(),
                progress -> publishProgress(context.lectureId(), progress));

        String storedFilename = result.file().getFileName().toString();
        String checksum = com.lectureprocessor.util.FileChecksum.sha256(result.file());
        lectureRepository.updateStoredMedia(context.lectureId(), storedFilename, result.sizeBytes(),
                source.audioOnly() ? "audio/*" : "video/*");
        context.setLecture(loader.lecture(context.lectureId()));
        mediaSourceRepository.save(source.withMetadata(source.provider(), source.externalId(),
                source.title(), source.uploader(), source.durationMs(), source.metadataJson(),
                checksum));
        context.setArtifact(new MediaArtifact(context.lectureId(), result.file(),
                source.audioOnly() ? "audio/*" : "video/*", result.sizeBytes(),
                context.metadata().durationMs(), MediaSourceKind.REMOTE_URL, source.originalUrl(),
                checksum));
        downloadStateRepository.save(new DownloadState(context.lectureId(), "FINISHED",
                result.sizeBytes(), result.sizeBytes(), 100.0, null, 0L, null,
                java.time.Instant.now()));
        return StageResult.completed(id(),
                ArtifactManifest.of(ArtifactManifest.required("media", storedFilename)), elapsed(start));
    }

    private void publishProgress(String lectureId, DownloadProgress progress) {
        progressRegistry.update(lectureId, current -> current == null ? null
                : current.withDownload(progress.status(), progress.downloadedBytes(),
                        progress.totalBytes(), progress.percent(), progress.speedBps(),
                        progress.etaSeconds()));
        downloadStateRepository.save(new DownloadState(lectureId, progress.status(),
                progress.downloadedBytes(), progress.totalBytes(), progress.percent(),
                progress.speedBps(), progress.etaSeconds(), null, java.time.Instant.now()));
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
