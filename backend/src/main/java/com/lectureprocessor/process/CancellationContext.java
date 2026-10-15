package com.lectureprocessor.process;

/**
 * Propagates the active job {@link CancellationToken} to external-tool
 * executors running on the same worker thread, so FFmpeg/Whisper processes can
 * be interrupted when a user cancels a job.
 *
 * <p>The coordinator sets the token for the duration of one pipeline run on its
 * worker thread; all external processes started synchronously by that thread
 * observe it. A thread-local is safe here because each job runs on its own
 * executor thread and does not spawn pipeline threads.</p>
 */
public final class CancellationContext {

    private static final ThreadLocal<CancellationToken> CURRENT = new ThreadLocal<>();

    private CancellationContext() {
    }

    public static void set(CancellationToken token) {
        CURRENT.set(token);
    }

    public static CancellationToken current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
