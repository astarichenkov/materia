package com.lectureprocessor.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lectureprocessor.process.StreamingProcessExecutor;

import tools.jackson.databind.ObjectMapper;

class YtDlpOutputLocatorTest {

    @TempDir
    Path dir;

    private YtDlpVideoDownloader downloader() {
        return new YtDlpVideoDownloader(new StreamingProcessExecutor(), null, null,
                new ObjectMapper());
    }

    @Test
    void audioOnlyAcceptsVideoFallbackProducedByYtDlp() throws Exception {
        Path file = dir.resolve("source.mp4");
        Files.write(file, new byte[] {1, 2, 3});

        Optional<Path> found = downloader().locateOutput(dir, true);

        assertTrue(found.isPresent());
        assertEquals(file, found.get());
    }

    @Test
    void ignoresPartialAndEmptyNames() throws Exception {
        Files.write(dir.resolve("source.mp4.part"), new byte[] {1});
        Files.write(dir.resolve("other.mp4"), new byte[] {1});

        assertTrue(downloader().locateOutput(dir, false).isEmpty());
    }
}
