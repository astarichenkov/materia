package com.lectureprocessor.codereview.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Small in-memory guards for the webhook endpoint:
 * <ul>
 *   <li>replay protection by delivery id (bounded TTL);</li>
 *   <li>per-repository rate limiting.</li>
 * </ul>
 * The database idempotency key remains the durable duplicate protection.
 */
@Component
public class WebhookGuards {

    private static final Duration DELIVERY_TTL = Duration.ofHours(6);
    private static final int MAX_REQUESTS_PER_MINUTE = 30;
    private static final int MAX_TRACKED_DELIVERIES = 5_000;

    private final Map<String, Instant> deliveries = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> requestWindows = new ConcurrentHashMap<>();
    private final AtomicLong lastSweep = new AtomicLong();

    /** Returns true when the delivery id was already processed recently. */
    public boolean isReplay(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return false;
        }
        sweep();
        Instant previous = deliveries.putIfAbsent(deliveryId, Instant.now());
        return previous != null && previous.isAfter(Instant.now().minus(DELIVERY_TTL));
    }

    /** Simple fixed-window rate limit per repository. */
    public boolean allow(String repository) {
        String key = repository == null ? "unknown" : repository;
        long now = System.currentTimeMillis();
        Deque<Long> window = requestWindows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) {
                window.pollFirst();
            }
            if (window.size() >= MAX_REQUESTS_PER_MINUTE) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        long last = lastSweep.get();
        if (now - last < 60_000 || !lastSweep.compareAndSet(last, now)) {
            return;
        }
        Instant cutoff = Instant.now().minus(DELIVERY_TTL);
        deliveries.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        if (deliveries.size() > MAX_TRACKED_DELIVERIES) {
            deliveries.clear();
        }
    }
}
