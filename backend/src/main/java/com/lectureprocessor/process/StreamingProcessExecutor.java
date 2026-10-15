package com.lectureprocessor.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Streaming, cancellable process executor used by long-running external tools
 * (yt-dlp). Unlike {@link DefaultProcessExecutor} it streams stdout/stderr line
 * by line through callbacks, keeps bounded output buffers and reacts to a
 * {@link CancellationToken} by destroying the whole process tree.
 *
 * <p>Existing {@link DefaultProcessExecutor} callers are unchanged.</p>
 */
@Component
public class StreamingProcessExecutor {

    private static final Logger log = LoggerFactory.getLogger(StreamingProcessExecutor.class);
    private static final int MAX_BUFFER_CHARS = 64 * 1024;
    private static final long CANCEL_POLL_MS = 200;

    public ProcessResult execute(List<String> command, Duration timeout, Path workingDirectory,
            CancellationToken cancellation, Consumer<String> stdoutLine,
            Consumer<String> stderrLine) {
        return execute(command, timeout, workingDirectory, cancellation, stdoutLine, stderrLine,
                MAX_BUFFER_CHARS);
    }

    /**
     * Same as {@link #execute(List, Duration, Path, CancellationToken, Consumer, Consumer)}
     * but with an explicit output-buffer bound. Metadata probes that return large
     * JSON documents may pass a larger bound; the value stays bounded either way.
     */
    public ProcessResult execute(List<String> command, Duration timeout, Path workingDirectory,
            CancellationToken cancellation, Consumer<String> stdoutLine,
            Consumer<String> stderrLine, int maxBufferChars) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        Instant start = Instant.now();
        Process process;
        try {
            log.debug("Starting streaming process: {}", command.getFirst());
            process = builder.start();
        } catch (IOException e) {
            throw new ProcessExecutionException("Не удалось запустить процесс: " + command.getFirst(), e);
        }

        StringBuilder outBuffer = new StringBuilder();
        StringBuilder errBuffer = new StringBuilder();
        BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        BufferedReader stderr = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        Thread outThread = pump(stdout, outBuffer, stdoutLine, maxBufferChars);
        Thread errThread = pump(stderr, errBuffer, stderrLine, maxBufferChars);

        boolean finished = false;
        boolean cancelled = false;
        boolean timedOut = false;
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                if (process.waitFor(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
                if (cancellation != null && cancellation.isCancelled()) {
                    cancelled = true;
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    timedOut = true;
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled = true;
        }

        if (!finished) {
            terminate(process);
        }
        joinQuietly(outThread);
        joinQuietly(errThread);
        Duration duration = Duration.between(start, Instant.now());

        if (cancelled) {
            throw new ProcessCancelledException("Процесс отменён: " + command.getFirst());
        }
        if (timedOut) {
            throw new ProcessExecutionException(
                    "Превышено время выполнения процесса (" + timeout.toMinutes() + " мин): "
                            + command.getFirst());
        }
        return new ProcessResult(process.exitValue(), outBuffer.toString(), errBuffer.toString(),
                duration, false);
    }

    private static Thread pump(BufferedReader reader, StringBuilder buffer, Consumer<String> lineConsumer,
            int maxBufferChars) {
        Thread thread = new Thread(() -> {
            try (reader) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (buffer.length() < maxBufferChars) {
                        buffer.append(line).append('\n');
                    }
                    if (lineConsumer != null) {
                        try {
                            lineConsumer.accept(line);
                        } catch (RuntimeException e) {
                            log.debug("Line consumer failed: {}", e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("Process stream read ended: {}", e.getMessage());
            }
        });
        thread.setDaemon(true);
        thread.setName("proc-stream-reader");
        thread.start();
        return thread;
    }

    static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Package-visible helper for tests. */
    static int bufferLimit() {
        return MAX_BUFFER_CHARS;
    }
}
