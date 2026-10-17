package com.lectureprocessor.multilingual;

import java.util.List;

import org.springframework.stereotype.Service;

import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.repository.StageCheckpointRepository;

/**
 * Application service for per-lecture language preferences, manual overrides
 * and translation status views.
 *
 * <p>Changing the source language invalidates transcription and everything
 * downstream (Whisper output depends on it). Correcting only the detected label
 * after transcription invalidates translation and downstream stages, never the
 * expensive Whisper run.</p>
 */
@Service
public class LectureLanguageService {

    private final LectureLanguageRepository languages;
    private final TranslationRepository translations;
    private final StageCheckpointRepository checkpoints;
    private final MultilingualProperties properties;

    public LectureLanguageService(LectureLanguageRepository languages,
            TranslationRepository translations, StageCheckpointRepository checkpoints,
            MultilingualProperties properties) {
        this.languages = languages;
        this.translations = translations;
        this.checkpoints = checkpoints;
        this.properties = properties;
    }

    public LectureLanguage get(String lectureId) {
        return languages.findOrDefault(lectureId, properties);
    }

    /**
     * Updates the requested language/translation mode. Invalidates transcription
     * (and downstream) because the recognition language may change.
     */
    public LectureLanguage update(String lectureId, LanguageMode languageMode,
            TranslationMode translationMode, boolean autoTranslate, String targetLanguage) {
        LectureLanguage current = get(lectureId);
        LectureLanguage updated = new LectureLanguage(lectureId, languageMode,
                languageMode.explicit() ? languageMode.whisperCode() : current.detectedLanguage(),
                null,
                languageMode.explicit() ? LanguageDetectionSource.MANUAL : current.detectionSource(),
                translationMode,
                targetLanguage == null || targetLanguage.isBlank() ? "ru" : targetLanguage,
                autoTranslate, java.time.Instant.now());
        languages.save(updated);
        checkpoints.invalidateFrom(lectureId, PipelineStage.TRANSCRIPTION);
        return updated;
    }

    /**
     * Manual correction of the detected language without forcing re-transcription.
     * Use this when Whisper's transcript is good but its reported language was
     * missing/incorrect.
     */
    public LectureLanguage correctDetected(String lectureId, String language) {
        LectureLanguage current = get(lectureId);
        LectureLanguage updated = current.withDetection(language, null,
                LanguageDetectionSource.MANUAL);
        languages.save(updated);
        checkpoints.invalidateFrom(lectureId, PipelineStage.TRANSLATION);
        return updated;
    }

    /** Latest translation metadata for the lecture card (or null). */
    public TranslationView view(String lectureId) {
        return translations.findLatest(lectureId)
                .map(t -> new TranslationView(get(lectureId), t,
                        translations.segments(t.id())))
                .orElseGet(() -> new TranslationView(get(lectureId), null, List.of()));
    }

    public record TranslationView(LectureLanguage language, LectureTranslation translation,
            List<TranslationSegment> segments) {
    }
}
