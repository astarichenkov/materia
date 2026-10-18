package com.lectureprocessor.download;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.media.MediaMetadata;
import com.lectureprocessor.support.TestProperties;

import tools.jackson.databind.ObjectMapper;

class YtDlpMetadataParsingTest {

    private final YtDlpVideoDownloader downloader = new YtDlpVideoDownloader(null,
            TestProperties.defaults("./target/test-data"), null, new ObjectMapper(),
            new YtDlpOptions(TestProperties.defaults("./target/test-data")));

    @Test
    void parsesStructuredMetadata() {
        String json = """
                {
                  "id": "abc123",
                  "title": "Лекция по Java",
                  "extractor_key": "Youtube",
                  "uploader": "Кафедра",
                  "duration": 123.5,
                  "width": 1920,
                  "height": 1080,
                  "filesize_approx": 104857600,
                  "formats": [
                    {"format_id":"137","ext":"mp4","height":1080,"width":1920,"filesize":52428800,
                     "vcodec":"avc1","acodec":"none","abr":null},
                    {"format_id":"140","ext":"m4a","height":null,"width":null,"filesize":1048576,
                     "vcodec":"none","acodec":"mp4a","abr":128.0}
                  ]
                }
                """;

        MediaMetadata metadata = downloader.parseMetadata(json, "https://youtu.be/abc123");

        assertThat(metadata.title()).isEqualTo("Лекция по Java");
        assertThat(metadata.externalId()).isEqualTo("abc123");
        assertThat(metadata.uploader()).isEqualTo("Кафедра");
        assertThat(metadata.durationMs()).isEqualTo(123500L);
        assertThat(metadata.height()).isEqualTo(1080);
        assertThat(metadata.estimatedBytes()).isEqualTo(104857600L);
        assertThat(metadata.formats()).hasSize(2);
        assertThat(metadata.formats().getFirst().formatId()).isEqualTo("137");
    }

    @Test
    void handlesMissingOptionalFields() {
        String json = "{\"title\":\"Minimal\",\"id\":\"x\",\"formats\":[]}";
        MediaMetadata metadata = downloader.parseMetadata(json, "https://rutube.ru/video/x");

        assertThat(metadata.title()).isEqualTo("Minimal");
        assertThat(metadata.durationMs()).isNull();
        assertThat(metadata.estimatedBytes()).isZero();
    }
}
