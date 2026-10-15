package com.lectureprocessor.process;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes external tools via {@link ProcessBuilder} without a shell, captures
 * stdout/stderr concurrently to avoid pipe deadlocks, enforces timeouts and
 * forcibly terminates the whole process tree on timeout or interruption.
 */
@Component
public class DefaultProcessExecutor implements ProcessExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultProcessExecutor.class);
    private static final long CANCEL_POLL_MS = 200;

    @Override
    public ProcessResult execute(List<String> command, Duration timeout, Path workingDirectory) {
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
            log.debug("Starting process: {}", String.join(" ", command));
            process = builder.start();
        } catch (IOException e) {
            throw new ProcessExecutionException("Не удалось запустить процесс: " + command.getFirst(), e);
        }

        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());

        boolean finished = false;
        boolean cancelled = false;
        boolean timedOut = false;
        CancellationToken cancellation = CancellationContext.current();
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

        String out = stdout.join();
        String err = stderr.join();
        Duration duration = Duration.between(start, Instant.now());

        if (cancelled) {
            throw new ProcessCancelledException("Процесс отменён: " + command.getFirst());
        }
        if (timedOut) {
            throw new ProcessExecutionException(
                    "Превышено время выполнения процесса (" + timeout.toMinutes() + " мин): " + command.getFirst());
        }
        return new ProcessResult(process.exitValue(), out, err, duration, false);
    }

    private static void terminate(Process process) {
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

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }
}
