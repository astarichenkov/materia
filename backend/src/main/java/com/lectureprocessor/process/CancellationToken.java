package com.lectureprocessor.process;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation primitive shared by the job coordinator, the pipeline
 * stages and the streaming process executor. A token is created per run and is
 * never reused.
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Throws {@link ProcessCancelledException} when cancellation was requested.
     * Used to stop stage work at safe boundaries.
     */
    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new ProcessCancelledException("Операция отменена пользователем");
        }
    }
}
