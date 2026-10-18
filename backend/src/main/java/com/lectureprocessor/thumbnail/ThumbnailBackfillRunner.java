package com.lectureprocessor.thumbnail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Bounded, idempotent thumbnail backfill for material imported before the
 * thumbnail cache existed.
 *
 * <p>Only a small batch (default 10) is scheduled at startup; existing READY and
 * NO_SOURCE rows are skipped, and the video is never downloaded again — a local
 * frame is extracted from the already stored media file. A larger batch can be
 * requested explicitly through the maintenance endpoint.</p>
 */
@Component
public class ThumbnailBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailBackfillRunner.class);

    private final ThumbnailRepository repository;
    private final ThumbnailJobQueue queue;
    private final ThumbnailProperties properties;

    public ThumbnailBackfillRunner(ThumbnailRepository repository, ThumbnailJobQueue queue,
            ThumbnailProperties properties) {
        this.repository = repository;
        this.queue = queue;
        this.properties = properties;
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        if (!properties.enabled() || !properties.backfillOnStartup()) {
            return;
        }
        int batch = Math.max(0, Math.min(properties.backfillBatch(), 100));
        if (batch == 0) {
            return;
        }
        try {
            var ids = repository.missingThumbnails(batch);
            int queued = 0;
            for (String id : ids) {
                if (queue.enqueue(id)) {
                    queued++;
                }
            }
            if (queued > 0) {
                log.info("Thumbnail backfill: {} of {} candidates queued", queued, ids.size());
            }
        } catch (RuntimeException e) {
            log.warn("Thumbnail backfill skipped: {}", e.getMessage());
        }
    }
}
