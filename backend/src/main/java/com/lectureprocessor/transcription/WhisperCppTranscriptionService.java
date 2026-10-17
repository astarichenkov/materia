package com.lectureprocessor.transcription;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.process.ProcessExecutor;
import com.lectureprocessor.process.ProcessResult;

/**
 * Real whisper.cpp integration. Invokes {@code whisper-cli} with an explicit
 * argument list and parses the produced JSON. The model path and binary are
 * configurable, so the same code works with medium, large-v3-turbo, etc.
 */
@Service
public class WhisperCppTranscriptionService implements TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(WhisperCppTranscriptionService.class);

    private final ProcessExecutor executor;
    private final AppProperties properties;
    private final WhisperTranscriptParser parser;
    private final TranscriptSanitizer sanitizer;

    public WhisperCppTranscriptionService(ProcessExecutor executor, AppProperties properties,
            WhisperTranscriptParser parser, TranscriptSanitizer sanitizer) {
        this.executor = executor;
        this.properties = properties;
        this.parser = parser;
        this.sanitizer = sanitizer;
    }

    @Override
    public Transcript transcribe(Path audio) {
        return transcribe(audio, properties.tools().whisperLanguage());
    }

    @Override
    public Transcript transcribe(Path audio, String language) {
        String modelPath = properties.tools().whisperModelPath();
        if (modelPath == null || modelPath.isBlank()) {
            throw new TranscriptionException(
                    "Не задан путь к модели Whisper (app.tools.whisper-model-path / WHISPER_MODEL_PATH)");
        }
        Path outputBase = audio.toAbsolutePath().getParent().resolve("transcript");
        List<String> command = new ArrayList<>(List.of(
                properties.tools().whisperPath(),
                "-m", modelPath,
                "-f", audio.toAbsolutePath().toString(),
                "-oj", "-of", outputBase.toString()));
        if (language != null && !language.isBlank()) {
            command.add("-l");
            command.add(language);
        }

        log.info("Transcribing {} with whisper.cpp", audio.getFileName());
        ProcessResult result = executor.execute(command, properties.tools().timeout(), null);
        if (!result.isSuccess()) {
            throw new TranscriptionException("whisper.cpp завершился с ошибкой: " + result.outputForError());
        }

        Path jsonPath = Path.of(outputBase + ".json");
        if (!Files.exists(jsonPath)) {
            throw new TranscriptionException("whisper.cpp не создал JSON: " + jsonPath);
        }
        try {
            String json = Files.readString(jsonPath, StandardCharsets.UTF_8);
            Transcript transcript = sanitizer.sanitize(parser.parse(json));
            log.info("Transcription parsed: {} speech segments (after sanitizing)",
                    transcript.segments().size());
            return transcript;
        } catch (IOException e) {
            throw new TranscriptionException("Не удалось прочитать JSON транскрипции", e);
        }
    }
}
