package com.lectureprocessor.pipeline.stage;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.Slide;
import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.document.DocxExporter;
import com.lectureprocessor.document.MarkdownBuilder;
import com.lectureprocessor.document.SlideImage;
import com.lectureprocessor.pipeline.ArtifactManifest;
import com.lectureprocessor.pipeline.Fingerprints;
import com.lectureprocessor.pipeline.JobContext;
import com.lectureprocessor.pipeline.PipelineDataLoader;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.pipeline.StageExecutor;
import com.lectureprocessor.pipeline.StageResult;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.SlideRepository;
import com.lectureprocessor.repository.TimingRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.text.StructuredNotes;

/**
 * Final document export: transcript Markdown, notes Markdown and DOCX (with
 * slides). Depends on the text and slide artifacts, so changing the prompt,
 * notes or slide set invalidates and re-runs only the export.
 */
@Component
public class ExportStage implements StageExecutor {

    public static final String DOCX_TEMPLATE_VERSION = "v1";

    private final MarkdownBuilder markdownBuilder;
    private final DocxExporter docxExporter;
    private final SlideRepository slideRepository;
    private final LectureRepository lectureRepository;
    private final FileStorageService storage;
    private final TimingRepository timings;
    private final PipelineDataLoader loader;

    public ExportStage(MarkdownBuilder markdownBuilder, DocxExporter docxExporter,
            SlideRepository slideRepository, LectureRepository lectureRepository,
            FileStorageService storage, TimingRepository timings, PipelineDataLoader loader) {
        this.markdownBuilder = markdownBuilder;
        this.docxExporter = docxExporter;
        this.slideRepository = slideRepository;
        this.lectureRepository = lectureRepository;
        this.storage = storage;
        this.timings = timings;
        this.loader = loader;
    }

    @Override
    public PipelineStage id() {
        return PipelineStage.EXPORT;
    }

    @Override
    public String inputFingerprint(JobContext context) {
        String notesKey = Fingerprints.mediaKey(
                storage.resolve(context.lectureId(), PipelineDataLoader.NOTES_JSON));
        String cleanedKey = Fingerprints.mediaKey(
                storage.resolve(context.lectureId(), PipelineDataLoader.CLEANED_TXT));
        String slideKey = slideRepository.findByLecture(context.lectureId()).stream()
                .map(s -> s.sequence() + ":" + s.timestampMs())
                .reduce("", (a, b) -> a + "," + b);
        return Fingerprints.of("export", notesKey, cleanedKey, DOCX_TEMPLATE_VERSION, slideKey);
    }

    @Override
    public StageResult execute(JobContext context) {
        long start = System.currentTimeMillis();
        Transcript transcript = context.transcript();
        if (transcript == null) {
            transcript = loader.transcript(context.lectureId());
        }
        StructuredNotes notes = context.notes();
        if (notes == null) {
            notes = loader.notes(context.lectureId());
        }
        if (transcript == null || notes == null) {
            return StageResult.failed(id(), "Недостаточно данных для экспорта (транскрипт или конспект)",
                    System.currentTimeMillis() - start);
        }
        Lecture lecture = context.lecture() != null ? context.lecture() : loader.lecture(context.lectureId());
        String cleaned = loader.cleanedText(context.lectureId());
        String title = "Конспект: " + (lecture.originalFilename() == null || lecture.originalFilename().isBlank()
                ? lecture.id() : lecture.originalFilename());

        String transcriptMd = markdownBuilder.rawTranscriptMarkdown(lecture, transcript);
        loader.writeString(context.lectureId(), PipelineDataLoader.TRANSCRIPT_MD, transcriptMd);
        loader.writeString(context.lectureId(), PipelineDataLoader.NOTES_MD, notes.markdown());

        List<SlideImage> slideImages = slideRepository.findByLecture(context.lectureId()).stream()
                .map(this::toSlideImage)
                .toList();
        Path docx = storage.resolve(context.lectureId(), PipelineDataLoader.DOCX);
        docxExporter.export(docx, title, notes, slideImages);

        Path rawTranscriptPath = storage.resolve(context.lectureId(), PipelineDataLoader.TRANSCRIPT_JSON);
        Path docxPath = docx;
        lectureRepository.updateOutputs(context.lectureId(),
                rawTranscriptPath.toAbsolutePath().toString(), cleaned, notes.markdown(),
                docxPath.toAbsolutePath().toString());
        context.setLecture(lecture.withOutputs(rawTranscriptPath.toAbsolutePath().toString(), cleaned,
                notes.markdown(), docxPath.toAbsolutePath().toString()));
        timings.record(context.lectureId(), TimingRepository.EXPORT, System.currentTimeMillis() - start);
        return StageResult.completed(id(),
                ArtifactManifest.of(
                        ArtifactManifest.required("transcript-md", PipelineDataLoader.TRANSCRIPT_MD),
                        ArtifactManifest.required("notes-md", PipelineDataLoader.NOTES_MD),
                        ArtifactManifest.required("docx", PipelineDataLoader.DOCX)),
                System.currentTimeMillis() - start);
    }

    private SlideImage toSlideImage(Slide slide) {
        return new SlideImage(slide.timestampMs(), Path.of(slide.imagePath()),
                "Слайд [" + Transcript.formatTimestamp(slide.timestampMs()) + "]");
    }
}
