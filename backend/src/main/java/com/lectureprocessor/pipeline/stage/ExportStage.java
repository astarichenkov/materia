package com.lectureprocessor.pipeline.stage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.Slide;
import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.document.DocxExporter;
import com.lectureprocessor.document.MarkdownBuilder;
import com.lectureprocessor.document.SlideImage;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageRepository;
import com.lectureprocessor.multilingual.MultilingualProperties;
import com.lectureprocessor.multilingual.TranslationMode;
import com.lectureprocessor.multilingual.TranslationRepository;
import com.lectureprocessor.multilingual.TranslationSegment;
import com.lectureprocessor.multilingual.TranslationStatus;
import com.lectureprocessor.processing.OutputVariantService;
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
 * Final document export: original transcript, primary notes (Russian when a
 * translation exists), and explicit original / translated / bilingual DOCX and
 * Markdown variants. Slides are embedded in every document; their timestamps
 * always refer to the original timeline.
 */
@Component
public class ExportStage implements StageExecutor {

    public static final String DOCX_TEMPLATE_VERSION = "v2-multilingual";

    private final MarkdownBuilder markdownBuilder;
    private final DocxExporter docxExporter;
    private final SlideRepository slideRepository;
    private final LectureRepository lectureRepository;
    private final FileStorageService storage;
    private final TimingRepository timings;
    private final PipelineDataLoader loader;
    private final TranslationRepository translations;
    private final LectureLanguageRepository languageRepository;
    private final MultilingualProperties multilingual;
    private final OutputVariantService variants;

    public ExportStage(MarkdownBuilder markdownBuilder, DocxExporter docxExporter,
            SlideRepository slideRepository, LectureRepository lectureRepository,
            FileStorageService storage, TimingRepository timings, PipelineDataLoader loader,
            TranslationRepository translations, LectureLanguageRepository languageRepository,
            MultilingualProperties multilingual, OutputVariantService variants) {
        this.markdownBuilder = markdownBuilder;
        this.docxExporter = docxExporter;
        this.slideRepository = slideRepository;
        this.lectureRepository = lectureRepository;
        this.storage = storage;
        this.timings = timings;
        this.loader = loader;
        this.translations = translations;
        this.languageRepository = languageRepository;
        this.multilingual = multilingual;
        this.variants = variants;
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
        String translationKey = translations.findLatest(context.lectureId())
                .map(t -> t.id() + ":" + t.status() + ":" + t.segmentsTranslated())
                .orElse("none");
        return Fingerprints.of("export", notesKey, cleanedKey, DOCX_TEMPLATE_VERSION, slideKey,
                translationKey);
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
        LectureLanguage language = languageRepository.findOrDefault(context.lectureId(), multilingual);
        String cleaned = loader.cleanedText(context.lectureId());
        String title = "Конспект: " + (lecture.originalFilename() == null
                || lecture.originalFilename().isBlank() ? lecture.id() : lecture.originalFilename());

        List<SlideImage> slideImages = slideRepository.findByLecture(context.lectureId()).stream()
                .map(this::toSlideImage)
                .toList();

        // Original transcript is always exported and never replaced.
        String originalMd = markdownBuilder.transcriptMarkdown(lecture, transcript,
                "Расшифровка лекции (оригинал)", "Расшифровка");
        loader.writeString(context.lectureId(), PipelineDataLoader.TRANSCRIPT_MD, originalMd);
        loader.writeString(context.lectureId(), PipelineDataLoader.TRANSCRIPT_ORIGINAL_MD, originalMd);
        loader.writeString(context.lectureId(), PipelineDataLoader.NOTES_MD, notes.markdown());

        Path docx = storage.resolve(context.lectureId(), PipelineDataLoader.DOCX);
        docxExporter.export(docx, title, notes, slideImages);
        // Keep a downloadable copy of this run next to the permanent working
        // artifacts, so a later re-generation never destroys the previous result.
        variants.attachExport(context.lectureId(), docx);

        List<ArtifactManifest.ArtifactRef> artifacts = new ArrayList<>(List.of(
                ArtifactManifest.required("transcript-md", PipelineDataLoader.TRANSCRIPT_MD),
                ArtifactManifest.required("transcript-original-md",
                        PipelineDataLoader.TRANSCRIPT_ORIGINAL_MD),
                ArtifactManifest.required("notes-md", PipelineDataLoader.NOTES_MD),
                ArtifactManifest.required("docx", PipelineDataLoader.DOCX)));

        // Original-language transcript document (readable even without translation).
        Path originalDocx = storage.resolve(context.lectureId(),
                PipelineDataLoader.NOTES_ORIGINAL_DOCX);
        docxExporter.exportTranscript(originalDocx,
                "Расшифровка: " + displayName(lecture), transcript.segments(), slideImages,
                "Язык оригинала: " + language.normalizedSource());
        artifacts.add(ArtifactManifest.optional("notes-original-docx",
                PipelineDataLoader.NOTES_ORIGINAL_DOCX));

        boolean translated = translationUsable(language);
        if (translated) {
            var translation = translations.findLatest(context.lectureId()).orElseThrow();
            List<TranslationSegment> stored = translations.segments(translation.id());
            Map<Integer, TranslationSegment> bySequence = new HashMap<>();
            stored.forEach(segment -> bySequence.put(segment.sequence(), segment));
            List<TranslationSegment> aligned = new ArrayList<>();
            for (var segment : transcript.segments()) {
                TranslationSegment value = bySequence.get(segment.sequence());
                aligned.add(value != null ? value
                        : TranslationSegment.pending(segment));
            }
            Transcript russian = new Transcript(aligned.stream()
                    .map(TranslationSegment::toTranscriptSegment).toList(), transcript.rawJson());
            String ruMd = markdownBuilder.transcriptMarkdown(lecture, russian,
                    "Расшифровка лекции (перевод на русский)", "Перевод");
            String bilingualMd = markdownBuilder.bilingualTranscriptMarkdown(lecture, transcript,
                    aligned);
            loader.writeString(context.lectureId(), PipelineDataLoader.TRANSCRIPT_RU_MD, ruMd);
            loader.writeString(context.lectureId(), PipelineDataLoader.TRANSCRIPT_BILINGUAL_MD,
                    bilingualMd);

            Path ruDocx = storage.resolve(context.lectureId(), PipelineDataLoader.NOTES_RU_DOCX);
            docxExporter.export(ruDocx, title + " (RU)", notes, slideImages);

            Path bilingualDocx = storage.resolve(context.lectureId(),
                    PipelineDataLoader.NOTES_BILINGUAL_DOCX);
            List<DocxExporter.BilingualLine> lines = transcript.segments().stream()
                    .map(segment -> {
                        TranslationSegment value = bySequence.get(segment.sequence());
                        String ru = value == null || value.translatedText() == null
                                || value.translatedText().isBlank()
                                        ? segment.text() : value.translatedText();
                        return new DocxExporter.BilingualLine(segment.startMs(), segment.endMs(),
                                segment.text(), ru);
                    })
                    .toList();
            docxExporter.exportBilingual(bilingualDocx,
                    "Расшифровка (оригинал + перевод): " + displayName(lecture), lines, slideImages);

            artifacts.add(ArtifactManifest.optional("transcript-ru-md",
                    PipelineDataLoader.TRANSCRIPT_RU_MD));
            artifacts.add(ArtifactManifest.optional("transcript-bilingual-md",
                    PipelineDataLoader.TRANSCRIPT_BILINGUAL_MD));
            artifacts.add(ArtifactManifest.optional("notes-ru-docx", PipelineDataLoader.NOTES_RU_DOCX));
            artifacts.add(ArtifactManifest.optional("notes-bilingual-docx",
                    PipelineDataLoader.NOTES_BILINGUAL_DOCX));
        }

        Path rawTranscriptPath = storage.resolve(context.lectureId(), PipelineDataLoader.TRANSCRIPT_JSON);
        Path docxPath = docx;
        lectureRepository.updateOutputs(context.lectureId(),
                rawTranscriptPath.toAbsolutePath().toString(), cleaned, notes.markdown(),
                docxPath.toAbsolutePath().toString());
        context.setLecture(lecture.withOutputs(rawTranscriptPath.toAbsolutePath().toString(), cleaned,
                notes.markdown(), docxPath.toAbsolutePath().toString()));
        timings.record(context.lectureId(), TimingRepository.EXPORT, System.currentTimeMillis() - start);
        return StageResult.completed(id(),
                new ArtifactManifest(List.copyOf(artifacts)),
                System.currentTimeMillis() - start);
    }

    private boolean translationUsable(LectureLanguage language) {
        if (!language.resolvedTranslationMode().wantsTranslation()
                && language.translationMode() == TranslationMode.ORIGINAL_ONLY) {
            return false;
        }
        return translations.findLatest(language.lectureId())
                .filter(t -> "ru".equals(t.targetLanguage()))
                .filter(t -> t.status() == TranslationStatus.COMPLETED
                        || t.status() == TranslationStatus.PARTIAL)
                .filter(t -> t.segmentsTranslated() > 0)
                .isPresent();
    }

    private SlideImage toSlideImage(Slide slide) {
        return new SlideImage(slide.timestampMs(), Path.of(slide.imagePath()),
                "Слайд [" + Transcript.formatTimestamp(slide.timestampMs()) + "]");
    }

    private static String displayName(Lecture lecture) {
        return lecture.originalFilename() == null || lecture.originalFilename().isBlank()
                ? lecture.id() : lecture.originalFilename();
    }
}
