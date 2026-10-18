package com.lectureprocessor.thumbnail;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thumbnail cache configuration. Kept in its own properties record so existing
 * components and tests that construct {@link com.lectureprocessor.config.AppProperties}
 * directly do not have to change.
 *
 * @param enabled           master switch (poster generation is best-effort)
 * @param width             target thumbnail width in pixels (height follows 16:9-ish source)
 * @param maxRemoteBytes    hard cap for a downloaded remote thumbnail
 * @param remoteTimeout     HTTP timeout for a remote thumbnail download
 * @param backfillBatch     bounded batch generated on startup for old lectures
 * @param backfillOnStartup whether the bounded startup backfill may run
 * @param failedRetryAfter minimum age of a FAILED row before an automatic retry
 * @param jpegQuality       JPEG quality used when re-encoding the thumbnail
 */
@ConfigurationProperties(prefix = "app.thumbnails")
public record ThumbnailProperties(
        boolean enabled,
        int width,
        long maxRemoteBytes,
        Duration remoteTimeout,
        int backfillBatch,
        boolean backfillOnStartup,
        Duration failedRetryAfter,
        float jpegQuality) {

    public static ThumbnailProperties defaults() {
        return new ThumbnailProperties(true, 480, 5L * 1024 * 1024, Duration.ofSeconds(20), 10,
                true, Duration.ofHours(6), 0.85f);
    }
}
