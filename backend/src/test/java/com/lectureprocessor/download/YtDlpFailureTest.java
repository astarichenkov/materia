package com.lectureprocessor.download;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class YtDlpFailureTest {

    @Test
    void botCheckIsPermanentAndActionable() {
        YtDlpFailure failure = YtDlpFailure.classify(
                "ERROR: [youtube] abc: Sign in to confirm you're not a bot. Use --cookies-from-browser",
                true);

        assertThat(failure.kind()).isEqualTo(YtDlpFailure.Kind.BOT_CHECK);
        assertThat(failure.retryable()).isFalse();
        assertThat(failure.message()).contains("не робот").contains("локальный файл");
    }

    @Test
    void privateVideoIsNotRetried() {
        YtDlpFailure failure = YtDlpFailure.classify(
                "ERROR: [youtube] abc: Private video. Sign in if you've been granted access", true);

        assertThat(failure.kind()).isEqualTo(YtDlpFailure.Kind.PRIVATE);
        assertThat(failure.retryable()).isFalse();
    }

    @Test
    void unavailableVideoIsNotRetried() {
        YtDlpFailure failure = YtDlpFailure.classify(
                "ERROR: [youtube] abc: Video unavailable", true);

        assertThat(failure.kind()).isEqualTo(YtDlpFailure.Kind.UNAVAILABLE);
        assertThat(failure.retryable()).isFalse();
    }

    @Test
    void formatFailureMentionsFfmpegWhenItIsMissing() {
        YtDlpFailure failure = YtDlpFailure.classify(
                "ERROR: [youtube] abc: Requested format is not available", false);

        assertThat(failure.kind()).isEqualTo(YtDlpFailure.Kind.FORMAT_UNAVAILABLE);
        assertThat(failure.retryable()).isFalse();
        assertThat(failure.message()).contains("FFmpeg");
    }

    @Test
    void missingFfmpegIsDetected() {
        YtDlpFailure failure = YtDlpFailure.classify(
                "WARNING: ffmpeg not found. Please install or provide the path", true);

        assertThat(failure.kind()).isEqualTo(YtDlpFailure.Kind.FFMPEG_MISSING);
        assertThat(failure.message()).contains("FFMPEG_PATH");
    }

    @Test
    void networkProblemsStayRetryable() {
        YtDlpFailure failure = YtDlpFailure.classify(
                "ERROR: Unable to download webpage: <urlopen error timed out>", true);

        assertThat(failure.kind()).isEqualTo(YtDlpFailure.Kind.NETWORK);
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    void unknownOutputKeepsTechnicalDetailButNoRawStderrInMessage() {
        String raw = "ERROR: something unexpected happened\nsecond line";
        YtDlpFailure failure = YtDlpFailure.classify(raw, true);

        assertThat(failure.kind()).isEqualTo(YtDlpFailure.Kind.UNKNOWN);
        assertThat(failure.detail()).isEqualTo(raw);
        assertThat(failure.message()).doesNotContain("second line");
    }

    @Test
    void exceptionCarriesReasonAndRetryFlag() {
        MediaDownloadException e = MediaDownloadException.fromFailure(
                YtDlpFailure.classify("ERROR: Sign in to confirm you're not a bot", true));

        assertThat(e.reason()).isEqualTo("BOT_CHECK");
        assertThat(e.retryable()).isFalse();
        assertThat(e.detail()).contains("Sign in");
    }
}
