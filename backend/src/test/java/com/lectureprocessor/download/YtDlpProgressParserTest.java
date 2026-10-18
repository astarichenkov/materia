package com.lectureprocessor.download;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class YtDlpProgressParserTest {

    @Test
    void parsesProgressLineWithTotals() {
        DownloadProgress progress = YtDlpProgressParser.parse(
                "download:progress|downloading|5242880|10485760|NA|1048576|5").orElseThrow();

        assertThat(progress.status()).isEqualTo("DOWNLOADING");
        assertThat(progress.downloadedBytes()).isEqualTo(5242880L);
        assertThat(progress.totalBytes()).isEqualTo(10485760L);
        assertThat(progress.percent()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(progress.speedBps()).isEqualTo(1048576.0);
        assertThat(progress.etaSeconds()).isEqualTo(5L);
    }


    @Test
    void parsesBareProgressLinePrintedByYtDlp() {
        DownloadProgress progress = YtDlpProgressParser.parse(
                "progress|downloading|35650560|686846859|NA|3750314.07|173").orElseThrow();

        assertThat(progress.downloadedBytes()).isEqualTo(35650560L);
        assertThat(progress.totalBytes()).isEqualTo(686846859L);
        assertThat(progress.percent()).isNotNull();
        assertThat(progress.etaSeconds()).isEqualTo(173L);
    }

    @Test
    void fallsBackToEstimatedTotal() {
        DownloadProgress progress = YtDlpProgressParser.parse(
                "download:progress|downloading|250|NA|1000|NA|NA").orElseThrow();

        assertThat(progress.totalBytes()).isEqualTo(1000L);
        assertThat(progress.percent()).isEqualTo(25.0);
        assertThat(progress.speedBps()).isNull();
    }

    @Test
    void marksFinishedStatus() {
        DownloadProgress progress = YtDlpProgressParser.parse(
                "download:progress|finished|1000|1000|NA|NA|NA").orElseThrow();
        assertThat(progress.status()).isEqualTo("FINISHED");
    }

    @Test
    void ignoresNonProgressLines() {
        assertThat(YtDlpProgressParser.parse("[download] Destination: source.mp4")).isEmpty();
        assertThat(YtDlpProgressParser.parse("download:other|value")).isEmpty();
        assertThat(YtDlpProgressParser.parse(null)).isEmpty();
    }
}
