package com.lectureprocessor.thumbnail;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Serial background queue for thumbnail generation.
 *
 * <p>Opening {@code /library} or {@code /lectures/{id}} never runs FFmpeg or a
 * network download inline: the page only enqueues work for lectures whose
 * thumbnail is missing. The queue deduplicates requests, so repeated page views
 * cannot start repeated extraction.</p>
 */
@Component
public class ThumbnailJobQueue {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailJobQueue.class);

    private final ThumbnailService service;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor;
    private final AtomicInteger pending = new AtomicInteger();

    public ThumbnailJobQueue(ThumbnailService service) {
        this.service = service;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "thumbnail-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Enqueues generation when the cache says it is worth trying. */
    public boolean enqueue(String lectureId) {
        if (lectureId == null || !service.needsGeneration(lectureId)) {
            return false;
        }
        if (!queued.add(lectureId)) {
            return false;
        }
        pending.incrementAndGet();
        executor.submit(() -> {
            try {
                service.generate(lectureId);
            } catch (RuntimeException e) {
                log.debug("Thumbnail job failed for {}: {}", lectureId, e.getMessage());
            } finally {
                queued.remove(lectureId);
                pending.decrementAndGet();
            }
        });
        return true;
    }

    /** Synchronous drain used by tests and the explicit backfill endpoint. */
    public int generateNow(Iterable<String> lectureIds) {
        int generated = 0;
        for (String lectureId : lectureIds) {
            if (!queued.add(lectureId)) {
                continue;
            }
            try {
                if (service.generate(lectureId).isPresent()) {
                    generated++;
                }
            } finally {
                queued.remove(lectureId);
            }
        }
        return generated;
    }

    public int pending() {
        return pending.get();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
