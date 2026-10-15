package com.lectureprocessor.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class DefaultProcessExecutorTest {

    private final DefaultProcessExecutor executor = new DefaultProcessExecutor();

    @Test
    void executesProcessAndCapturesOutput() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessResult result = executor.execute(List.of(java, "-version"), Duration.ofSeconds(30), null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stderr()).contains("version");
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void throwsForMissingBinary() {
        assertThatThrownBy(() -> executor.execute(
                List.of("definitely-not-a-real-binary-12345"), Duration.ofSeconds(5), null))
                .isInstanceOf(ProcessExecutionException.class);
    }

    @Test
    void capturesNonZeroExitCode() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessResult result = executor.execute(List.of(java, "-bad-flag-xyz"),
                Duration.ofSeconds(30), null);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void cancellationStopsLongRunningProcess() throws InterruptedException {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> command = windows
                ? List.of("ping", "-n", "30", "127.0.0.1")
                : List.of("sleep", "30");
        CancellationToken token = new CancellationToken();
        CancellationContext.set(token);
        try {
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                token.cancel();
            });
            canceller.start();
            long start = System.currentTimeMillis();
            assertThatThrownBy(() -> executor.execute(command, Duration.ofSeconds(60), null))
                    .isInstanceOf(ProcessCancelledException.class);
            assertThat(System.currentTimeMillis() - start).isLessThan(10_000);
            canceller.join(2_000);
        } finally {
            CancellationContext.clear();
        }
    }
}
