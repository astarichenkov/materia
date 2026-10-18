package com.lectureprocessor.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.media.MediaSourceKind;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.MediaSourceRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.support.TestProperties;
import com.lectureprocessor.video.FfmpegService;

import tools.jackson.databind.ObjectMapper;

class ThumbnailServiceTest {

    @TempDir
    Path tempDir;

    private AppProperties properties() {
        AppProperties base = TestProperties.defaults(tempDir.toString());
        return new AppProperties(base.tools(), base.storage(), base.upload(), base.processing(),
                base.ollama(), base.slides(), base.chat(), base.llm(), base.downloader(),
                base.assistant(), base.mcp(), base.deepseek(), base.security(), base.gitea(),
                base.codeReview());
    }

    private Lecture lecture(String contentType, boolean slides) throws Exception {
        Instant now = Instant.now();
        return new Lecture("L1", "lecture.mp4", "source.mp4", 100, contentType,
                JobStatus.COMPLETED, null, 60_000L, false, slides, null, null, null, null, now, now);
    }

    @Test
    void extractsInformativeLocalFrameAndCachesIt() throws Exception {
        FileStorageService storage = new FileStorageService(properties());
        Path source = storage.resolve("L1", "source.mp4");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[] {1, 2, 3});

        FfmpegService ffmpeg = mock(FfmpegService.class);
        // First candidate is a fully black frame, second one is informative.
        when(ffmpeg.extractThumbnailFrame(any(), any(), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    Path output = invocation.getArgument(1);
                    long timestamp = invocation.getArgument(2);
                    Files.createDirectories(output.getParent());
                    ImageIO.write(frame(timestamp <= 6_000L ? false : true), "jpg", output.toFile());
                    return output;
                });
        ThumbnailRepository repository = new ThumbnailRepository(mockJdbc());
        LectureRepository lectures = mock(LectureRepository.class);
        MediaSourceRepository sources = mock(MediaSourceRepository.class);
        when(lectures.findById("L1")).thenReturn(Optional.of(lecture("video/mp4", false)));
        when(sources.findByLecture("L1")).thenReturn(Optional.empty());

        ThumbnailService service = new ThumbnailService(ffmpeg, storage, repository, lectures,
                sources, ThumbnailProperties.defaults(), mock(com.lectureprocessor.download.UrlSafetyValidator.class),
                new ObjectMapper());

        Optional<LectureThumbnail> result = service.generate("L1");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(ThumbnailStatus.READY);
        assertThat(result.get().source()).isEqualTo("VIDEO_FRAME");
        assertThat(service.isReady("L1")).isTrue();
        assertThat(Files.size(storage.resolve("L1", ThumbnailService.RELATIVE_PATH))).isPositive();
        // The candidate files are cleaned up after selection.
        assertThat(Files.exists(storage.resolve("L1", "thumb-candidates"))).isFalse();
    }

    @Test
    void audioOnlyLectureIsRecordedAsNoSource() throws Exception {
        FileStorageService storage = new FileStorageService(properties());
        Path source = storage.resolve("L1", "source.mp3");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[] {1, 2, 3});

        FfmpegService ffmpeg = mock(FfmpegService.class);
        ThumbnailRepository repository = new ThumbnailRepository(mockJdbc());
        LectureRepository lectures = mock(LectureRepository.class);
        MediaSourceRepository sources = mock(MediaSourceRepository.class);
        Instant now = Instant.now();
        Lecture audio = new Lecture("L1", "lecture.mp3", "source.mp3", 100, "audio/mpeg",
                JobStatus.COMPLETED, null, 60_000L, false, false, null, null, null, null, now, now);
        when(lectures.findById("L1")).thenReturn(Optional.of(audio));
        when(sources.findByLecture("L1")).thenReturn(Optional.of(MediaSource.local("L1", "lecture.mp3")
                .withMetadata(null, null, null, null, 60_000L, null, null)));

        ThumbnailService service = new ThumbnailService(ffmpeg, storage, repository, lectures,
                sources, ThumbnailProperties.defaults(),
                mock(com.lectureprocessor.download.UrlSafetyValidator.class), new ObjectMapper());

        Optional<LectureThumbnail> result = service.generate("L1");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(ThumbnailStatus.NO_SOURCE);
        assertThat(service.needsGeneration("L1")).isFalse();
    }

    @Test
    void scorePrefersDetailedFrameOverBlackFrame() throws Exception {
        double black = ThumbnailService.score(frame(false));
        double detailed = ThumbnailService.score(frame(true));
        assertThat(detailed).isGreaterThan(black);
    }

    @Test
    void scalingKeepsAspectAndProducesRgbImage() throws Exception {
        BufferedImage source = new BufferedImage(1280, 720, BufferedImage.TYPE_4BYTE_ABGR);
        BufferedImage scaled = ThumbnailService.scale(source, 480);
        assertThat(scaled.getWidth()).isEqualTo(480);
        assertThat(scaled.getHeight()).isEqualTo(270);
        assertThat(scaled.getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
    }

    private static BufferedImage frame(boolean detailed) {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(detailed ? Color.WHITE : Color.BLACK);
        g.fillRect(0, 0, 320, 180);
        if (detailed) {
            g.setColor(Color.BLACK);
            g.fillRect(20, 20, 120, 80);
            g.setColor(Color.DARK_GRAY);
            g.fillRect(180, 60, 100, 100);
        }
        g.dispose();
        return image;
    }

    /**
     * Real SQLite table for the cache row: the service asserts READY state and
     * the file check, so a mocked JdbcTemplate would hide regressions.
     */
    private org.springframework.jdbc.core.JdbcTemplate mockJdbc() {
        try {
            String url = "jdbc:sqlite:" + tempDir.resolve("thumb-" + System.nanoTime() + ".db");
            org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url);
            org.springframework.jdbc.core.JdbcTemplate jdbc =
                    new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            jdbc.execute("""
                    CREATE TABLE lecture_thumbnail (
                        lecture_id TEXT PRIMARY KEY, status TEXT NOT NULL, source TEXT NOT NULL,
                        relative_path TEXT, size_bytes INTEGER NOT NULL DEFAULT 0, error TEXT,
                        updated_at TEXT NOT NULL)
                    """);
            return jdbc;
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        }
    }
}
