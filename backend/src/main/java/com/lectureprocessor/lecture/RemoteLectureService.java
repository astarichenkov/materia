package com.lectureprocessor.lecture;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.download.MediaQuality;
import com.lectureprocessor.download.VideoMetadataService;
import com.lectureprocessor.download.VideoPlatform;
import com.lectureprocessor.download.UrlSafetyValidator;
import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.media.MediaMetadata;
import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.process.CancellationToken;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.MediaSourceRepository;

/**
 * Creates and previews lectures from remote video URLs (YouTube, VK Video, VK,
 * Rutube). Metadata is fetched before any download so the UI can show a preview
 * and the user can pick quality / audio-only.
 */
@Service
public class RemoteLectureService {

    private static final Logger log = LoggerFactory.getLogger(RemoteLectureService.class);

    private final LectureRepository lectureRepository;
    private final MediaSourceRepository mediaSourceRepository;
    private final VideoMetadataService metadataService;
    private final UrlSafetyValidator validator;

    public RemoteLectureService(LectureRepository lectureRepository,
            MediaSourceRepository mediaSourceRepository, VideoMetadataService metadataService,
            UrlSafetyValidator validator) {
        this.lectureRepository = lectureRepository;
        this.mediaSourceRepository = mediaSourceRepository;
        this.metadataService = metadataService;
        this.validator = validator;
    }

    /** Validates the platform and returns preview metadata without downloading. */
    public MediaMetadata preview(String url) {
        validator.validate(url);
        return metadataService.probe(url, new CancellationToken());
    }

    public Lecture createFromUrl(String url, String quality, boolean audioOnly, boolean cleanTranscript,
            boolean extractSlides) {
        VideoPlatform platform = validator.validate(url);
        MediaMetadata metadata = metadataService.probe(url, new CancellationToken());
        MediaQuality selectedQuality = MediaQuality.fromLabel(quality);
        boolean slides = extractSlides && !audioOnly;

        String id = UUID.randomUUID().toString();
        String title = metadata.title() != null && !metadata.title().isBlank()
                ? metadata.title() : platform.displayName();
        String originalName = LectureService.sanitizeFilename(title) + ".mp4";
        Instant now = Instant.now();
        Lecture lecture = new Lecture(id, originalName, "", 0L, null, JobStatus.UPLOADED, null,
                metadata.durationMs(), cleanTranscript, slides, null, null, null, null, now, now);
        lectureRepository.save(lecture);

        MediaSource source = MediaSource.remote(id, url, selectedQuality.label(), audioOnly)
                .withMetadata(metadata.provider() != null ? metadata.provider() : platform.displayName(),
                        metadata.externalId(), metadata.title(), metadata.uploader(),
                        metadata.durationMs(), metadata.rawJson(), null);
        mediaSourceRepository.save(source);
        log.info("Remote lecture {} created from {} ({}), quality={}, audioOnly={}",
                id, platform.displayName(), metadata.externalId(), selectedQuality.label(), audioOnly);
        return lecture;
    }
}
