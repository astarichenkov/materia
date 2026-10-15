package com.lectureprocessor.pipeline.stage;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.domain.Slide;
import com.lectureprocessor.pipeline.ArtifactManifest;
import com.lectureprocessor.pipeline.Fingerprints;
import com.lectureprocessor.pipeline.JobContext;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.pipeline.StageExecutor;
import com.lectureprocessor.pipeline.StageResult;
import com.lectureprocessor.repository.SlideRepository;
import com.lectureprocessor.slides.SlideExtractionService;
import com.lectureprocessor.storage.FileStorageService;

/**
 * Explicit slide extraction stage. Bounded at the FFmpeg level (see
 * {@code FfmpegService.extractFrames}). The stage is optional: a failure is
 * recorded as a warning and the job continues to export (marked PARTIAL) rather
 * than silently completing without slides.
 */
@Component
public class SlideExtractionStage implements StageExecutor {

    private final SlideExtractionService slideExtraction;
    private final SlideRepository slideRepository;
    private final FileStorageService storage;
    private final AppProperties properties;

    public SlideExtractionStage(SlideExtractionService slideExtraction, SlideRepository slideRepository,
            FileStorageService storage, AppProperties properties) {
        this.slideExtraction = slideExtraction;
        this.slideRepository = slideRepository;
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public PipelineStage id() {
        return PipelineStage.SLIDE_EXTRACTION;
    }

    @Override
    public boolean optional() {
        return true;
    }

    @Override
    public boolean requiredForExport() {
        return false;
    }

    @Override
    public String inputFingerprint(JobContext context) {
        AppProperties.Slides slides = properties.slides();
        boolean extract = context.lecture() != null && context.lecture().extractSlides();
        return Fingerprints.of("slide-extraction",
                Fingerprints.mediaKey(context.artifact() == null ? null : context.artifact().path()),
                String.valueOf(extract), String.valueOf(slides.intervalSeconds()),
                String.valueOf(slides.maxImages()), String.valueOf(slides.hashDistanceThreshold()));
    }

    @Override
    public StageResult execute(JobContext context) {
        long start = System.currentTimeMillis();
        if (context.lecture() == null || !context.lecture().extractSlides()) {
            return StageResult.completed(id(), ArtifactManifest.empty(),
                    System.currentTimeMillis() - start);
        }
        List<Slide> slides = slideExtraction.extract(context.lectureId(), context.artifact().path());
        List<ArtifactManifest.ArtifactRef> refs = new ArrayList<>();
        for (Slide slide : slides) {
            String relative = storage.relative(context.lectureId(), java.nio.file.Path.of(slide.imagePath()));
            refs.add(ArtifactManifest.required("slide-" + slide.sequence(), relative));
        }
        if (slides.isEmpty()) {
            context.addWarning("Слайды запрошены, но не найдены уникальные кадры презентации");
            context.setPartialSuccess(true);
        }
        return StageResult.completed(id(), new ArtifactManifest(refs),
                System.currentTimeMillis() - start);
    }
}
