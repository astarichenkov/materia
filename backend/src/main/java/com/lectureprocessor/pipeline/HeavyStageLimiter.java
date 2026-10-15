package com.lectureprocessor.pipeline;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.process.CancellationToken;
import com.lectureprocessor.process.ProcessCancelledException;

/**
 * Bounds the number of concurrent CPU/GPU-heavy stages (Whisper transcription,
 * LLM text processing) so they do not oversubscribe RAM/VRAM together with
 * benchmark runs. Default is a single permit.
 */
@Component
public class HeavyStageLimiter {

    private final Semaphore permits;

    public HeavyStageLimiter(AppProperties properties) {
        this.permits = new Semaphore(Math.max(1, properties.downloader().heavyStagePermits()));
    }

    public <T> T run(CancellationToken cancellation, java.util.function.Supplier<T> action) {
        acquire(cancellation);
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }

    private void acquire(CancellationToken cancellation) {
        while (true) {
            cancellation.throwIfCancelled();
            try {
                if (permits.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProcessCancelledException("Ожидание ресурса отменено");
            }
        }
    }
}
