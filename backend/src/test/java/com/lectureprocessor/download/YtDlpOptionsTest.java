package com.lectureprocessor.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.support.TestProperties;

class YtDlpOptionsTest {

    @TempDir
    Path dir;

    @Test
    void defaultsDisableCookiesAndRemoteComponents() {
        YtDlpOptions options = new YtDlpOptions(properties("disabled", "", "", "none", "", "", ""));

        List<String> args = options.commonArguments();

        assertThat(args).containsExactly("yt-dlp", "--ignore-config", "--no-playlist",
                "--socket-timeout", "30");
        assertThat(args).noneMatch(a -> a.contains("--cookies"));
        assertThat(args).noneMatch(a -> a.contains("--remote-components"));
        assertThat(options.ffmpegAvailable()).isFalse();
        assertThat(options.ffmpegLocation()).isNull();
    }

    @Test
    void explicitNodeRuntimeIsResolvedToAnAbsolutePath() throws IOException {
        Path node = Files.createFile(dir.resolve("node.exe"));

        List<String> args = new YtDlpOptions(
                properties("node", node.toString(), "", "none", "", "", "")).commonArguments();

        assertThat(args).containsSubsequence("--js-runtimes", "node:" + node);
    }

    @Test
    void autoRuntimeFallsBackWhenNothingIsInstalled() {
        YtDlpOptions options = new YtDlpOptions(properties("auto",
                dir.resolve("missing-runtime").toString(), "", "none", "", "", ""));

        assertThat(options.jsRuntime()).isEmpty();
    }

    @Test
    void remoteComponentsRequireExplicitConfiguration() {
        YtDlpOptions disabled = new YtDlpOptions(properties("disabled", "", "", "none", "", "", ""));
        YtDlpOptions enabled = new YtDlpOptions(
                properties("disabled", "", "ejs:github", "none", "", "", ""));

        assertThat(disabled.commonArguments()).noneMatch(a -> a.contains("--remote-components"));
        assertThat(enabled.commonArguments()).contains("--remote-components", "ejs:github");
    }

    @Test
    void browserCookiesAreBuiltOnlyWhenConfigured() {
        YtDlpOptions options = new YtDlpOptions(properties("disabled", "", "", "browser",
                "chrome", "C:\\profile\\Default", ""));

        assertThat(options.commonArguments())
                .contains("--cookies-from-browser", "chrome:C:\\profile\\Default");
    }

    @Test
    void browserCookiesWithoutBrowserNameAreRejected() {
        YtDlpOptions options = new YtDlpOptions(properties("disabled", "", "", "browser", "",
                "", ""));

        assertThatThrownBy(options::commonArguments)
                .isInstanceOf(MediaDownloadException.class)
                .satisfies(e -> assertThat(((MediaDownloadException) e).retryable()).isFalse());
    }

    @Test
    void fileCookiesRequireAnExistingFile() {
        YtDlpOptions missing = new YtDlpOptions(properties("disabled", "", "", "file", "",
                "", dir.resolve("cookies.txt").toString()));

        assertThatThrownBy(missing::commonArguments)
                .isInstanceOf(MediaDownloadException.class);
    }

    @Test
    void ffmpegDirectoryIsResolvedAndPassedToYtDlp() throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path ffmpeg = Files.createFile(bin.resolve("ffmpeg.exe"));

        AppProperties base = TestProperties.defaults("./target/test-data");
        YtDlpOptions options = new YtDlpOptions(TestProperties.withTools(base,
                new AppProperties.Tools(ffmpeg.toString(), "ffprobe", "whisper-cli",
                        "model.bin", "ru", Duration.ofMinutes(5))));

        assertThat(options.ffmpegAvailable()).isTrue();
        assertThat(options.ffmpegLocation()).isEqualTo(ffmpeg.toString());
    }

    private AppProperties properties(String jsRuntime, String jsRuntimePath,
            String remoteComponents, String cookiesMode, String browser, String profile,
            String cookiesFile) {
        return TestProperties.withDownloader(TestProperties.defaults("./target/test-data"),
                new AppProperties.Downloader("yt-dlp", Duration.ofSeconds(30),
                        Duration.ofMinutes(5), 1024L, "720p", false, 1, jsRuntime,
                        jsRuntimePath, remoteComponents, cookiesMode, browser, profile,
                        cookiesFile));
    }
}
