package com.lectureprocessor.download;

import java.util.List;
import java.util.Locale;

/**
 * Classifies raw yt-dlp output into a small set of user-facing failures. The
 * goal is to stop retrying permanent access problems (bot check, private video,
 * missing FFmpeg) and to show an actionable message instead of raw extractor
 * stderr.
 *
 * <p>The classifier is deliberately pattern-based and conservative: anything it
 * does not recognise stays retryable and keeps the original technical detail in
 * the log.</p>
 */
public record YtDlpFailure(Kind kind, String message, boolean retryable, String detail) {

    public enum Kind {
        BOT_CHECK,
        LOGIN_REQUIRED,
        PRIVATE,
        UNAVAILABLE,
        GEO_BLOCKED,
        FORMAT_UNAVAILABLE,
        FFMPEG_MISSING,
        JS_RUNTIME_MISSING,
        NETWORK,
        UNKNOWN
    }

    private static final List<String> BOT_CHECK_PATTERNS = List.of(
            "sign in to confirm you're not a bot",
            "sign in to confirm you’re not a bot",
            "confirm you're not a bot",
            "confirm you’re not a bot");

    private static final List<String> LOGIN_PATTERNS = List.of(
            "please sign in",
            "login required",
            "this video requires authentication",
            "cookies are needed",
            "sign in to view");

    private static final List<String> PRIVATE_PATTERNS = List.of(
            "private video",
            "this video is private",
            "members-only",
            "members only",
            "join this channel");

    private static final List<String> UNAVAILABLE_PATTERNS = List.of(
            "video unavailable",
            "this video is unavailable",
            "has been removed",
            "no longer available",
            "does not exist",
            "content is not available",
            "account associated with this video has been terminated");

    private static final List<String> GEO_PATTERNS = List.of(
            "not available in your country",
            "not available in your region",
            "geo restricted",
            "geo-restricted");

    private static final List<String> FORMAT_PATTERNS = List.of(
            "requested format is not available",
            "no video formats found",
            "requested format not available");

    private static final List<String> FFMPEG_PATTERNS = List.of(
            "ffmpeg not found",
            "ffmpeg is not installed",
            "ffmpeg not installed",
            "you have requested merging of multiple formats but ffmpeg is not installed",
            "merging of multiple formats but ffmpeg");

    private static final List<String> JS_RUNTIME_PATTERNS = List.of(
            "no supported javascript runtime",
            "javascript runtime could not be found");

    private static final List<String> NETWORK_PATTERNS = List.of(
            "unable to download webpage",
            "unable to download video data",
            "temporary failure in name resolution",
            "connection reset",
            "connection refused",
            "connection aborted",
            "timed out",
            "timeout",
            "http error 429",
            "http error 500",
            "http error 502",
            "http error 503",
            "http error 504",
            "urlopen error");

    private static final String HINT_OPEN_OR_UPLOAD =
            "Откройте видео на исходной платформе или загрузите локальный файл.";

    public static YtDlpFailure classify(String output, boolean ffmpegAvailable) {
        String raw = output == null ? "" : output.strip();
        String text = raw.toLowerCase(Locale.ROOT);
        String detail = tail(raw, 1200);

        if (containsAny(text, BOT_CHECK_PATTERNS)) {
            return new YtDlpFailure(Kind.BOT_CHECK,
                    "YouTube требует подтвердить, что запрос сделал не робот "
                            + "(Sign in to confirm you're not a bot). Импорт остановлен. "
                            + HINT_OPEN_OR_UPLOAD
                            + " Для авторизованного доступа администратор может включить cookies "
                            + "браузера (YTDLP_COOKIES_MODE=browser).",
                    false, detail);
        }
        if (containsAny(text, PRIVATE_PATTERNS)) {
            return new YtDlpFailure(Kind.PRIVATE,
                    "Видео приватное или доступно только участникам канала. " + HINT_OPEN_OR_UPLOAD,
                    false, detail);
        }
        if (containsAny(text, LOGIN_PATTERNS)) {
            return new YtDlpFailure(Kind.LOGIN_REQUIRED,
                    "Платформа требует входа в аккаунт для этого видео. " + HINT_OPEN_OR_UPLOAD
                            + " При необходимости включите cookies браузера в настройках.",
                    false, detail);
        }
        if (containsAny(text, GEO_PATTERNS)) {
            return new YtDlpFailure(Kind.GEO_BLOCKED,
                    "Видео недоступно в этом регионе. " + HINT_OPEN_OR_UPLOAD, false, detail);
        }
        if (containsAny(text, UNAVAILABLE_PATTERNS)) {
            return new YtDlpFailure(Kind.UNAVAILABLE,
                    "Видео недоступно на платформе: удалено, скрыто или ограничено. "
                            + HINT_OPEN_OR_UPLOAD,
                    false, detail);
        }
        if (containsAny(text, FFMPEG_PATTERNS)) {
            return new YtDlpFailure(Kind.FFMPEG_MISSING,
                    "yt-dlp не нашёл FFmpeg: объединение видео и аудио невозможно. "
                            + "Задайте FFMPEG_PATH (путь к ffmpeg или к каталогу bin) и повторите импорт.",
                    false, detail);
        }
        if (containsAny(text, FORMAT_PATTERNS)) {
            String message = ffmpegAvailable
                    ? "Не удалось выбрать формат видео: платформа не отдала доступный поток. "
                            + "Попробуйте другое качество или загрузите локальный файл."
                    : "Не удалось выбрать формат видео: FFmpeg не найден, поэтому отдельные "
                            + "видео- и аудиопотоки нельзя объединить. Задайте FFMPEG_PATH "
                            + "или загрузите локальный файл.";
            return new YtDlpFailure(Kind.FORMAT_UNAVAILABLE, message, false, detail);
        }
        if (containsAny(text, JS_RUNTIME_PATTERNS)) {
            return new YtDlpFailure(Kind.JS_RUNTIME_MISSING,
                    "yt-dlp не нашёл JavaScript runtime (Node.js или Deno): часть форматов YouTube "
                            + "недоступна. Задайте YTDLP_JS_RUNTIME=node при наличии Node.js "
                            + "или загрузите локальный файл.",
                    false, detail);
        }
        if (containsAny(text, NETWORK_PATTERNS)) {
            return new YtDlpFailure(Kind.NETWORK,
                    "Сетевая ошибка при обращении к платформе. Проверьте соединение и повторите "
                            + "импорт позже.",
                    true, detail);
        }
        return new YtDlpFailure(Kind.UNKNOWN,
                "yt-dlp не смог импортировать видео. " + HINT_OPEN_OR_UPLOAD, true, detail);
    }

    private static boolean containsAny(String text, List<String> patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static String tail(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        return stripped.length() <= maxChars
                ? stripped
                : stripped.substring(stripped.length() - maxChars);
    }
}
