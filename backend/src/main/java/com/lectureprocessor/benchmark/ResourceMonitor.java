package com.lectureprocessor.benchmark;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Best-effort resource sampling for the local LLM. VRAM comes from
 * {@code nvidia-smi} (total GPU memory used) and RAM from the Ollama process
 * working set. Every metric is optional: if the tool is missing or the platform
 * is unsupported, {@code null} is returned and the UI shows {@code N/A}.
 */
@Component
public class ResourceMonitor {

    private static final Logger log = LoggerFactory.getLogger(ResourceMonitor.class);
    private static final long SAMPLE_INTERVAL_MS = 500;

    public Long vramUsedMb() {
        try {
            Process process = new ProcessBuilder("nvidia-smi",
                    "--query-gpu=memory.used", "--format=csv,noheader,nounits")
                    .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                process.waitFor();
                if (line == null || line.isBlank()) {
                    return null;
                }
                return Long.parseLong(line.strip());
            }
        } catch (Exception e) {
            log.debug("nvidia-smi unavailable: {}", e.getMessage());
            return null;
        }
    }

    public Long ollamaRamMb() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? windowsOllamaRamMb()
                : unixOllamaRamMb();
    }

    private Long windowsOllamaRamMb() {
        try {
            Process process = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH")
                    .redirectErrorStream(true).start();
            long totalKb = 0;
            boolean found = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase().contains("ollama") || line.toLowerCase().contains("llama")) {
                        String[] parts = line.split("\",\"");
                        if (parts.length >= 5) {
                            // The memory column is locale-dependent ("336,244 K", "33 072 КБ", ...).
                            String digits = parts[4].replaceAll("[^0-9]", "");
                            if (!digits.isEmpty()) {
                                totalKb += Long.parseLong(digits);
                                found = true;
                            }
                        }
                    }
                }
            }
            process.waitFor();
            return found ? totalKb / 1024 : null;
        } catch (Exception e) {
            log.debug("tasklist unavailable: {}", e.getMessage());
            return null;
        }
    }

    private Long unixOllamaRamMb() {
        try {
            Process process = new ProcessBuilder("sh", "-c", "ps -eo rss,comm | grep -iE 'ollama|llama'")
                    .redirectErrorStream(true).start();
            long totalKb = 0;
            boolean found = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.strip().split("\\s+");
                    if (parts.length >= 1 && parts[0].matches("\\d+")) {
                        totalKb += Long.parseLong(parts[0]);
                        found = true;
                    }
                }
            }
            process.waitFor();
            return found ? totalKb / 1024 : null;
        } catch (Exception e) {
            log.debug("ps unavailable: {}", e.getMessage());
            return null;
        }
    }

    /** Samples peak RAM/VRAM in the background for the duration of a run. */
    public Sampler startSampler() {
        return new Sampler();
    }

    public final class Sampler implements AutoCloseable {

        private final AtomicLong peakRam = new AtomicLong();
        private final AtomicLong peakVram = new AtomicLong();
        private volatile boolean running = true;
        private final Thread thread;

        private Sampler() {
            sample();
            this.thread = new Thread(this::loop, "benchmark-resource-sampler");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void loop() {
            while (running) {
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                sample();
            }
        }

        private void sample() {
            Long ram = ollamaRamMb();
            if (ram != null) {
                peakRam.accumulateAndGet(ram, Math::max);
            }
            Long vram = vramUsedMb();
            if (vram != null) {
                peakVram.accumulateAndGet(vram, Math::max);
            }
        }

        public Long peakRamMb() {
            return peakRam.get() == 0 ? null : peakRam.get();
        }

        public Long peakVramMb() {
            return peakVram.get() == 0 ? null : peakVram.get();
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
        }
    }
}
