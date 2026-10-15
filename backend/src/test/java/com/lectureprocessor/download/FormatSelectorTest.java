package com.lectureprocessor.download;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FormatSelectorTest {

    @Test
    void audioOnlyUsesBestAudio() {
        assertThat(FormatSelector.formatExpression(MediaQuality.P720, true, true))
                .isEqualTo("bestaudio/best");
    }

    @Test
    void videoWithFfmpegUsesCappedStreamsAndFallback() {
        assertThat(FormatSelector.formatExpression(MediaQuality.P720, false, true))
                .isEqualTo("bv*[height<=720]+ba/b[height<=720]/b");
        assertThat(FormatSelector.formatExpression(MediaQuality.P1080, false, true))
                .contains("height<=1080");
    }

    @Test
    void autoQualityHasNoHeightCap() {
        assertThat(FormatSelector.formatExpression(MediaQuality.AUTO, false, true))
                .isEqualTo("bv*+ba/b");
    }

    @Test
    void withoutFfmpegFallsBackToProgressive() {
        assertThat(FormatSelector.formatExpression(MediaQuality.P480, false, false))
                .isEqualTo("b[height<=480]/b");
        assertThat(FormatSelector.formatExpression(MediaQuality.AUTO, false, false)).isEqualTo("b");
    }

    @Test
    void qualityLabelParsingDefaultsTo720p() {
        assertThat(MediaQuality.fromLabel("1080p")).isEqualTo(MediaQuality.P1080);
        assertThat(MediaQuality.fromLabel("auto")).isEqualTo(MediaQuality.AUTO);
        assertThat(MediaQuality.fromLabel(null)).isEqualTo(MediaQuality.P720);
        assertThat(MediaQuality.fromLabel("bogus")).isEqualTo(MediaQuality.P720);
    }
}
