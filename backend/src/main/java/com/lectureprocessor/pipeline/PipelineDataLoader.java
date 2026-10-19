package com.lectureprocessor.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.text.StructuredNotes;
import com.lectureprocessor.transcription.WhisperTranscriptParser;

import tools.jackson.databind.ObjectMapper;

/**
 * Loads intermediate artifacts from disk/DB so stages that are skipped by a valid
 * checkpoint can still restore the data their downstream stages need.
 */
@Component
public class PipelineDataLoader {

    public static final String AUDIO = "audio.wav";
    public static final String TRANSCRIPT_JSON = "transcript.json";
    public static final String TRANSCRIPT_MD = "transcript.md";
    public static final String NOTES_JSON = "notes.json";
    public static final String NOTES_MD = "notes.md";
    public static final String CLEANED_TXT = "cleaned.txt";
    public static final String NOTES_QUALITY_JSON = "notes-quality.json";
    public static final String DOCX = "notes.docx";
    public static final String TRANSLATION_JSON = "translation.json";
    public static final String TRANSCRIPT_ORIGINAL_MD = "transcript.original.md";
    public static final String TRANSCRIPT_RU_MD = "transcript.ru.md";
    public static final String TRANSCRIPT_BILINGUAL_MD = "transcript.bilingual.md";
    public static final String NOTES_ORIGINAL_DOCX = "notes.original.docx";
    public static final String NOTES_RU_DOCX = "notes.ru.docx";
    public static final String NOTES_BILINGUAL_DOCX = "notes.bilingual.docx";

    private final FileStorageService storage;
    private final LectureRepository lectureRepository;
    private final WhisperTranscriptParser transcriptParser;
    private final ObjectMapper objectMapper;
    private final com.lectureprocessor.repository.MediaSourceRepository mediaSourceRepository;

    public PipelineDataLoader(FileStorageService storage, LectureRepository lectureRepository,
            WhisperTranscriptParser transcriptParser, ObjectMapper objectMapper,
            com.lectureprocessor.repository.MediaSourceRepository mediaSourceRepository) {
        this.storage = storage;
        this.lectureRepository = lectureRepository;
        this.transcriptParser = transcriptParser;
        this.objectMapper = objectMapper;
        this.mediaSourceRepository = mediaSourceRepository;
    }

    public Lecture lecture(String lectureId) {
        return lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("Лекция не найдена: " + lectureId));
    }

    public com.lectureprocessor.media.MediaSource source(String lectureId) {
        return mediaSourceRepository.findByLecture(lectureId).orElse(null);
    }

    /**
     * Rebuilds a {@link com.lectureprocessor.media.MediaArtifact} from persisted
     * state so stages skipped by a valid checkpoint still see the media file.
     */
    public com.lectureprocessor.media.MediaArtifact artifact(String lectureId) {
        Lecture lecture = lecture(lectureId);
        com.lectureprocessor.media.MediaSource source = source(lectureId);
        if (lecture.storedFilename() == null || lecture.storedFilename().isBlank()) {
            return new com.lectureprocessor.media.MediaArtifact(lectureId, null,
                    lecture.contentType(), 0L, lecture.durationMs(),
                    source == null ? com.lectureprocessor.media.MediaSourceKind.LOCAL_FILE : source.kind(),
                    source == null ? lecture.originalFilename() : source.originalUrl(),
                    source == null ? null : source.checksum());
        }
        Path path = storage.resolve(lectureId, lecture.storedFilename());
        long size = 0;
        if (Files.isRegularFile(path)) {
            try {
                size = Files.size(path);
            } catch (IOException ignored) {
                size = 0;
            }
        }
        return new com.lectureprocessor.media.MediaArtifact(lectureId, path, lecture.contentType(),
                size, lecture.durationMs(),
                source == null ? com.lectureprocessor.media.MediaSourceKind.LOCAL_FILE : source.kind(),
                source == null ? lecture.originalFilename() : source.originalUrl(),
                source == null ? null : source.checksum());
    }

    public Transcript transcript(String lectureId) {
        Path path = storage.resolve(lectureId, TRANSCRIPT_JSON);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return transcriptParser.parse(json);
        } catch (IOException e) {
            return null;
        }
    }

    /** Persists the heuristic quality report next to the notes. */
    public void writeQuality(String lectureId, com.lectureprocessor.text.TextQualityReport report) {
        if (report == null) {
            return;
        }
        Path path = storage.resolve(lectureId, NOTES_QUALITY_JSON);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, objectMapper.writeValueAsString(report),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Не удалось сохранить отчёт качества", e);
        }
    }

    public com.lectureprocessor.text.TextQualityReport quality(String lectureId) {
        Path path = storage.resolve(lectureId, NOTES_QUALITY_JSON);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8),
                    com.lectureprocessor.text.TextQualityReport.class);
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    public StructuredNotes notes(String lectureId) {
        Path path = storage.resolve(lectureId, NOTES_JSON);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8),
                    StructuredNotes.class);
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    public String cleanedText(String lectureId) {
        Path path = storage.resolve(lectureId, CLEANED_TXT);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public void writeString(String lectureId, String relativePath, String content) {
        Path path = storage.resolve(lectureId, relativePath);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось записать файл: " + path, e);
        }
    }

    public void writeNotes(String lectureId, StructuredNotes notes) {
        try {
            Path path = storage.resolve(lectureId, NOTES_JSON);
            Files.createDirectories(path.getParent());
            Files.writeString(path, objectMapper.writeValueAsString(notes), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось записать notes.json", e);
        }
    }
}
