package com.lectureprocessor.slides;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.domain.Slide;
import com.lectureprocessor.repository.SlideRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.video.FfmpegService;
import com.lectureprocessor.video.FfmpegService.ExtractedFrame;

class SlideExtractionServiceTest {

    @Test
    void removesNonAdjacentDuplicatesUsingAllKeptHashes() throws Exception {
        Path work = Path.of("target/test-data/slides-" + System.nanoTime());
        Files.createDirectories(work);
        Path first = writeSlide(work.resolve("a.jpg"), true);
        Path distinct = writeSlide(work.resolve("b.jpg"), false);
        Path repeated = writeSlide(work.resolve("a-again.jpg"), true);

        FfmpegService ffmpeg = mock(FfmpegService.class);
        SlideRepository repository = mock(SlideRepository.class);
        AppProperties properties = props(work.toString());
        FileStorageService storage = new FileStorageService(properties);
        when(ffmpeg.extractFrames(any(), any(), anyDouble(), anyInt())).thenReturn(List.of(
                new ExtractedFrame(0, 0, first),
                new ExtractedFrame(1, 4_000, distinct),
                new ExtractedFrame(2, 8_000, repeated)));
        when(repository.findByLecture("L1")).thenReturn(List.of());

        new SlideExtractionService(ffmpeg, storage, repository, properties).extract("L1", work.resolve("v.mp4"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Slide>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceAll(eq("L1"), captor.capture());
        // A, B, A -> the repeated A must be dropped even though it is not adjacent.
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).extracting(Slide::timestampMs).containsExactly(0L, 4_000L);
    }

    private static Path writeSlide(Path path, boolean squareLeft) throws Exception {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(squareLeft ? Color.WHITE : Color.BLACK);
        g.fillRect(0, 0, 320, 180);
        g.setColor(squareLeft ? Color.BLACK : Color.WHITE);
        g.fillRect(squareLeft ? 40 : 180, 40, 100, 100);
        g.dispose();
        ImageIO.write(image, "jpg", path.toFile());
        return path;
    }

    private static AppProperties props(String root) {
        return new AppProperties(
                new AppProperties.Tools("ffmpeg", "ffprobe", "whisper", "model", "ru", Duration.ofMinutes(5)),
                new AppProperties.Storage(root),
                new AppProperties.Upload(1024),
                new AppProperties.Processing(1, 1, 300, 60, 300, 60, 50),
                new AppProperties.Ollama("http://localhost:11434", "m", "e", 768, Duration.ofMinutes(1), true),
                new AppProperties.Slides(2.0, 10, 6),
                new AppProperties.Chat(4, 2000, 5, 0.2),
                new AppProperties.Llm(java.util.Map.of(
                        "ollama", new AppProperties.Provider(true, "Ollama (local)"))));
    }
}
