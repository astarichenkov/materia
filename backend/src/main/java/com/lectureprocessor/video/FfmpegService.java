package com.lectureprocessor.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.process.ProcessExecutor;
import com.lectureprocessor.process.ProcessExecutionException;
import com.lectureprocessor.process.ProcessResult;

/**
 * Thin, safe wrapper around FFmpeg/FFprobe. All invocations use argument lists
 * (never a shell), so user-controlled paths cannot be interpreted as commands.
 */
@Service
public class FfmpegService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegService.class);

    private final ProcessExecutor executor;
    private final AppProperties properties;

    public FfmpegService(ProcessExecutor executor, AppProperties properties) {
        this.executor = executor;
        this.properties = properties;
    }

    public void extractAudio(Path video, Path outputWav) {
        ProcessResult result = executor.execute(List.of(
                properties.tools().ffmpegPath(),
                "-hide_banner", "-y",
                "-i", video.toAbsolutePath().toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                outputWav.toAbsolutePath().toString()),
                properties.tools().timeout(), null);
        requireSuccess(result, "FFmpeg: извлечение аудио");
        if (!Files.exists(outputWav) || sizeOf(outputWav) == 0) {
            throw new ProcessExecutionException("FFmpeg не создал аудиофайл: " + outputWav);
        }
        log.info("Audio extracted: {}", outputWav.getFileName());
    }

    public long probeDurationMs(Path video) {
        ProcessResult result = executor.execute(List.of(
                properties.tools().ffprobePath(),
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                video.toAbsolutePath().toString()),
                Duration.ofMinutes(2), null);
        if (!result.isSuccess()) {
            log.warn("ffprobe failed, duration unknown: {}", result.outputForError());
            return 0;
        }
        String value = result.stdout().strip();
        try {
            return Math.round(Double.parseDouble(value) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Extracts one JPEG frame every {@code intervalSeconds}. Returns frame paths
     * ordered by time; index N corresponds to timestamp N * intervalSeconds.
     */
    public List<ExtractedFrame> extractFrames(Path video, Path outputDir, double intervalSeconds, int maxFrames) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new ProcessExecutionException("Не удалось создать каталог кадров: " + outputDir, e);
        }
        ProcessResult result = executor.execute(List.of(
                properties.tools().ffmpegPath(),
                "-hide_banner", "-y",
                "-i", video.toAbsolutePath().toString(),
                "-vf", "fps=1/" + intervalSeconds,
                "-frames:v", Integer.toString(Math.max(1, maxFrames)),
                "-q:v", "3",
                outputDir.resolve("frame_%05d.jpg").toAbsolutePath().toString()),
                properties.tools().timeout(), null);
        requireSuccess(result, "FFmpeg: извлечение кадров");

        List<Path> frames;
        try (Stream<Path> stream = Files.list(outputDir)) {
            frames = stream.filter(p -> p.getFileName().toString().endsWith(".jpg"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .limit(maxFrames)
                    .toList();
        } catch (IOException e) {
            throw new ProcessExecutionException("Не удалось прочитать кадры из " + outputDir, e);
        }
        List<ExtractedFrame> extracted = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            extracted.add(new ExtractedFrame(i, Math.round(i * intervalSeconds * 1000), frames.get(i)));
        }
        return extracted;
    }

    private static void requireSuccess(ProcessResult result, String operation) {
        if (!result.isSuccess()) {
            throw new ProcessExecutionException(operation + " завершилась с ошибкой: " + result.outputForError());
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    public record ExtractedFrame(int index, long timestampMs, Path path) {
    }
}
