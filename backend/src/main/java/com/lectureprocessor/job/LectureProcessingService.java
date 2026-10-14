package com.lectureprocessor.job;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.Slide;
import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.document.DocxExporter;
import com.lectureprocessor.document.MarkdownBuilder;
import com.lectureprocessor.document.SlideImage;
import com.lectureprocessor.llm.LlmException;
import com.lectureprocessor.llm.LlmOptions;
import com.lectureprocessor.llm.LlmProvider;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmSettingsService;
import com.lectureprocessor.rag.EmbeddingIndexService;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.SegmentRepository;
import com.lectureprocessor.repository.TimingRepository;
import com.lectureprocessor.slides.SlideExtractionService;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.text.StructuredNotes;
import com.lectureprocessor.text.TextProcessingService;
import com.lectureprocessor.transcription.TranscriptionService;
import com.lectureprocessor.video.FfmpegService;

/**
 * Executes the full processing pipeline for one lecture. Every stage persists
 * its status so progress is observable and survives restarts.
 *
 * <pre>
 *   UPLOADED -> EXTRACTING_AUDIO -> TRANSCRIBING -> PROCESSING_TEXT -> EXPORTING -> COMPLETED
 * </pre>
 */
@Service
public class LectureProcessingService {

    private static final Logger log = LoggerFactory.getLogger(LectureProcessingService.class);

    private final LectureRepository lectureRepository;
    private final SegmentRepository segmentRepository;
    private final FileStorageService storage;
    private final FfmpegService ffmpeg;
    private final TranscriptionService transcription;
    private final TextProcessingService textProcessing;
    private final MarkdownBuilder markdownBuilder;
    private final DocxExporter docxExporter;
    private final SlideExtractionService slideExtraction;
    private final EmbeddingIndexService embeddingIndex;
    private final LlmProvider llm;
    private final LlmSettingsService llmSettings;
    private final TimingRepository timings;

    public LectureProcessingService(LectureRepository lectureRepository, SegmentRepository segmentRepository,
            FileStorageService storage, FfmpegService ffmpeg, TranscriptionService transcription,
            TextProcessingService textProcessing, MarkdownBuilder markdownBuilder, DocxExporter docxExporter,
            SlideExtractionService slideExtraction, EmbeddingIndexService embeddingIndex, LlmProvider llm,
            LlmSettingsService llmSettings, TimingRepository timings) {
        this.lectureRepository = lectureRepository;
        this.segmentRepository = segmentRepository;
        this.storage = storage;
        this.ffmpeg = ffmpeg;
        this.transcription = transcription;
        this.textProcessing = textProcessing;
        this.markdownBuilder = markdownBuilder;
        this.docxExporter = docxExporter;
        this.slideExtraction = slideExtraction;
        this.embeddingIndex = embeddingIndex;
        this.llm = llm;
        this.llmSettings = llmSettings;
        this.timings = timings;
    }

    public void process(String lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("Лекция не найдена: " + lectureId));
        long totalStart = System.nanoTime();
        try {
            Path source = storage.resolve(lectureId, lecture.storedFilename());
            LlmRunConfig settings = llmSettings.current();
            LlmOptions options = settings.toOptions();

            // 1. Audio extraction
            lecture = advance(lecture, JobStatus.EXTRACTING_AUDIO);
            long ffmpegStart = System.nanoTime();
            Path audio = storage.resolve(lectureId, "audio.wav");
            ffmpeg.extractAudio(source, audio);
            long durationMs = ffmpeg.probeDurationMs(source);
            lecture = withDuration(lecture, durationMs);
            timings.record(lectureId, TimingRepository.FFMPEG, elapsedMs(ffmpegStart));

            // 2. Transcription
            lecture = advance(lecture, JobStatus.TRANSCRIBING);
            long whisperStart = System.nanoTime();
            Transcript transcript = transcription.transcribe(audio);
            if (transcript.isEmpty()) {
                throw new IllegalStateException("Транскрипция пустая — в видео не распознана речь");
            }
            persistTranscript(lectureId, transcript);
            lecture = withRawTranscript(lecture, storage.resolve(lectureId, "transcript.json"));
            timings.record(lectureId, TimingRepository.WHISPER, elapsedMs(whisperStart));

            // 3. Text processing (LLM)
            if (!llm.isAvailable()) {
                throw new LlmException("Локальная модель Ollama недоступна. Запустите Ollama и загрузите "
                        + "модель (переменные OLLAMA_BASE_URL / OLLAMA_CHAT_MODEL).");
            }
            lecture = advance(lecture, JobStatus.PROCESSING_TEXT);
            long llmStart = System.nanoTime();
            String notesPrompt = settings.promptTemplate() == null
                    ? com.lectureprocessor.text.PromptTemplates.STRUCTURED_NOTES
                    : settings.promptTemplate();
            String cleaned = lecture.cleanTranscript()
                    ? textProcessing.cleanTranscript(transcript.segments(), options)
                    : null;
            StructuredNotes notes = textProcessing.generateStructuredNotes(transcript.segments(), options,
                    notesPrompt);
            timings.record(lectureId, TimingRepository.LLM, elapsedMs(llmStart));

            // 4. Local RAG index (non-fatal: chat can be rebuilt later)
            indexSafely(lectureId, transcript);

            // 5. Export documents
            lecture = advance(lecture, JobStatus.EXPORTING);
            long exportStart = System.nanoTime();
            Path transcriptMd = storage.resolve(lectureId, "transcript.md");
            writeString(transcriptMd, markdownBuilder.rawTranscriptMarkdown(lecture, transcript));
            Path notesMd = storage.resolve(lectureId, "notes.md");
            writeString(notesMd, notes.markdown());

            List<SlideImage> slideImages = List.of();
            if (lecture.extractSlides()) {
                slideImages = extractSlidesSafely(lectureId, source);
            }
            Path docx = storage.resolve(lectureId, "notes.docx");
            docxExporter.export(docx, "Конспект: " + lecture.originalFilename(), notes, slideImages);
            timings.record(lectureId, TimingRepository.EXPORT, elapsedMs(exportStart));
            timings.record(lectureId, TimingRepository.TOTAL, elapsedMs(totalStart));

            lecture = withResults(lecture, cleaned, notes.markdown(), docx);
            lecture = advance(lecture, JobStatus.COMPLETED);
            log.info("Lecture {} processed successfully", lectureId);
        } catch (Exception e) {
            log.error("Processing failed for lecture {}: {}", lectureId, e.getMessage(), e);
            lectureRepository.save(lecture.withError(e.getMessage()));
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Обработка лекции не удалась", e);
        }
    }

    private void persistTranscript(String lectureId, Transcript transcript) {
        segmentRepository.replaceAll(lectureId, transcript.segments());
        Path json = storage.resolve(lectureId, "transcript.json");
        writeString(json, transcript.rawJson());
    }

    private void indexSafely(String lectureId, Transcript transcript) {
        try {
            embeddingIndex.index(lectureId, transcript.segments());
        } catch (Exception e) {
            log.warn("Embedding index failed for lecture {} (chat will be unavailable): {}",
                    lectureId, e.getMessage());
        }
    }

    private List<SlideImage> extractSlidesSafely(String lectureId, Path source) {
        try {
            return slideExtraction.extract(lectureId, source).stream()
                    .map(this::toSlideImage)
                    .toList();
        } catch (Exception e) {
            log.warn("Slide extraction failed for lecture {}: {}", lectureId, e.getMessage());
            return List.of();
        }
    }

    private SlideImage toSlideImage(Slide slide) {
        return new SlideImage(slide.timestampMs(), Path.of(slide.imagePath()),
                "Слайд [" + com.lectureprocessor.domain.Transcript.formatTimestamp(slide.timestampMs()) + "]");
    }

    private Lecture advance(Lecture lecture, JobStatus status) {
        Lecture updated = lecture.withStatus(status);
        lectureRepository.save(updated);
        log.info("Lecture {} -> {}", lecture.id(), status);
        return updated;
    }

    private Lecture withDuration(Lecture lecture, long durationMs) {
        Lecture updated = new Lecture(lecture.id(), lecture.originalFilename(), lecture.storedFilename(),
                lecture.sizeBytes(), lecture.contentType(), lecture.status(), lecture.errorMessage(),
                durationMs, lecture.cleanTranscript(), lecture.extractSlides(), lecture.rawTranscriptPath(),
                lecture.cleanedText(), lecture.notesMarkdown(), lecture.docxPath(),
                lecture.createdAt(), java.time.Instant.now());
        lectureRepository.save(updated);
        return updated;
    }

    private Lecture withRawTranscript(Lecture lecture, Path rawTranscript) {
        Lecture updated = new Lecture(lecture.id(), lecture.originalFilename(), lecture.storedFilename(),
                lecture.sizeBytes(), lecture.contentType(), lecture.status(), lecture.errorMessage(),
                lecture.durationMs(), lecture.cleanTranscript(), lecture.extractSlides(),
                rawTranscript.toAbsolutePath().toString(), lecture.cleanedText(), lecture.notesMarkdown(),
                lecture.docxPath(), lecture.createdAt(), java.time.Instant.now());
        lectureRepository.save(updated);
        return updated;
    }

    private Lecture withResults(Lecture lecture, String cleaned, String notes, Path docx) {
        Lecture updated = new Lecture(lecture.id(), lecture.originalFilename(), lecture.storedFilename(),
                lecture.sizeBytes(), lecture.contentType(), lecture.status(), lecture.errorMessage(),
                lecture.durationMs(), lecture.cleanTranscript(), lecture.extractSlides(),
                lecture.rawTranscriptPath(), cleaned, notes, docx.toAbsolutePath().toString(),
                lecture.createdAt(), java.time.Instant.now());
        lectureRepository.save(updated);
        return updated;
    }

    private static void writeString(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось записать файл: " + path, e);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
