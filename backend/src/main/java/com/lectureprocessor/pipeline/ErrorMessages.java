package com.lectureprocessor.pipeline;

import java.util.regex.Pattern;

/**
 * Makes persisted pipeline errors safe and readable.
 *
 * <p>Stage failures often come from external tools (FFmpeg, whisper.cpp,
 * yt-dlp) and contain absolute filesystem paths, argument dumps and multi-line
 * stderr. The UI must show the reason without exposing the server's internal
 * layout, so paths are replaced with a relative placeholder and the message is
 * bounded.</p>
 */
public final class ErrorMessages {

    private static final int MAX_LENGTH = 700;
    /** Windows drive path, POSIX absolute path or file: URI inside a message. */
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?:file:)?(?:[A-Za-z]:[\\\\/][^\\s\\\"']*|/(?:home|tmp|var|opt|mnt|root|srv|etc)/[^\\s\\\"']*)");
    private static final Pattern WHITESPACE = Pattern.compile("[\\t\\r\\f]+");

    private ErrorMessages() {
    }

    public static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "Неизвестная ошибка обработки";
        }
        String value = ABSOLUTE_PATH.matcher(message).replaceAll("<путь>");
        value = WHITESPACE.matcher(value).replaceAll(" ");
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            value = value.substring(0, MAX_LENGTH) + "…";
        }
        return value;
    }
}
