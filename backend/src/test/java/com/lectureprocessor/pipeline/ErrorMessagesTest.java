package com.lectureprocessor.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorMessagesTest {

    @Test
    void removesAbsoluteFilesystemPathsFromUserVisibleErrors() {
        String raw = "FFmpeg: извлечение аудио завершилась с ошибкой: Error opening input file "
                + "C:\\Projects\\lecture-processor\\data\\lectures\\abc\\source.mp4. "
                + "Error opening input files: Invalid data found";

        String safe = ErrorMessages.sanitize(raw);

        assertThat(safe).doesNotContain("C:\\Projects").contains("<путь>")
                .contains("Invalid data found");
    }

    @Test
    void removesPosixAndFileUriPaths() {
        String safe = ErrorMessages.sanitize(
                "Cannot read /home/anton/data/source.mp4 and file:/var/lib/app/notes.json");
        assertThat(safe).doesNotContain("/home/anton").doesNotContain("/var/lib")
                .contains("<путь>");
    }

    @Test
    void boundsMessageLengthAndHandlesEmptyInput() {
        assertThat(ErrorMessages.sanitize("x".repeat(2000))).hasSizeLessThanOrEqualTo(701);
        assertThat(ErrorMessages.sanitize(null)).isEqualTo("Неизвестная ошибка обработки");
        assertThat(ErrorMessages.sanitize("  ")).isEqualTo("Неизвестная ошибка обработки");
    }
}
