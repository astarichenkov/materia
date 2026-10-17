package com.lectureprocessor.multilingual;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.domain.TranscriptSegment;

import tools.jackson.databind.ObjectMapper;

class LanguageDetectorTest {

    private final LanguageDetector detector = new LanguageDetector(new ObjectMapper());

    private static Transcript transcript(String... texts) {
        List<TranscriptSegment> segments = new java.util.ArrayList<>();
        int sequence = 0;
        for (String text : texts) {
            segments.add(new TranscriptSegment(sequence++, 0, 1000, text));
        }
        return new Transcript(segments, null);
    }

    @Test
    void readsLanguageReportedByWhisperWithoutInventingConfidence() {
        String json = """
                {"params":{"language":"en"},"result":{"language":"en"}}
                """;
        LanguageDetector.Detection detection = detector.detect(json,
                transcript("hello world"), LanguageMode.AUTO);
        assertThat(detection.language()).isEqualTo("en");
        assertThat(detection.source()).isEqualTo(LanguageDetectionSource.WHISPER_DETECTED);
        assertThat(detection.confidence()).isNull();
    }

    @Test
    void readsConfidenceOnlyWhenWhisperActuallyReportsIt() {
        String json = """
                {"result":{"language":"english","language_probability":0.93}}
                """;
        LanguageDetector.Detection detection = detector.detect(json,
                transcript("hello world"), LanguageMode.AUTO);
        assertThat(detection.language()).isEqualTo("en");
        assertThat(detection.confidence()).isEqualTo(0.93);
    }

    @Test
    void manualModeOverridesWhisper() {
        LanguageDetector.Detection detection = detector.detect(
                "{\"result\":{\"language\":\"ru\"}}", transcript("hello"), LanguageMode.ENGLISH);
        assertThat(detection.language()).isEqualTo("en");
        assertThat(detection.source()).isEqualTo(LanguageDetectionSource.MANUAL);
        assertThat(detection.confidence()).isNull();
    }

    @Test
    void heuristicDetectsRussianAndEnglish() {
        assertThat(detector.detect(null, transcript("Это лекция про машинное обучение"),
                LanguageMode.AUTO).language()).isEqualTo("ru");
        assertThat(detector.detect(null, transcript("This lecture is about machine learning"),
                LanguageMode.AUTO).language()).isEqualTo("en");
        assertThat(detector.detect(null, transcript("12345 !!!"), LanguageMode.AUTO).language())
                .isEqualTo("und");
    }

    @Test
    void russianSourceIsNeverTranslatedIntoRussian() {
        LectureLanguage russian = new LectureLanguage("l1", LanguageMode.AUTO, "ru", null,
                LanguageDetectionSource.WHISPER_DETECTED, TranslationMode.TRANSLATE_TO_RUSSIAN,
                "ru", true, java.time.Instant.now());
        assertThat(russian.resolvedTranslationMode()).isEqualTo(TranslationMode.ORIGINAL_ONLY);
        assertThat(russian.translationWanted()).isFalse();
    }

    @Test
    void englishSourceIsTranslatedOnlyWhenRequestedOrAutoEnabled() {
        LectureLanguage auto = new LectureLanguage("l1", LanguageMode.AUTO, "en", null,
                LanguageDetectionSource.WHISPER_DETECTED, TranslationMode.ORIGINAL_ONLY, "ru",
                true, java.time.Instant.now());
        assertThat(auto.resolvedTranslationMode()).isEqualTo(TranslationMode.TRANSLATE_TO_RUSSIAN);

        LectureLanguage off = new LectureLanguage("l1", LanguageMode.AUTO, "en", null,
                LanguageDetectionSource.WHISPER_DETECTED, TranslationMode.ORIGINAL_ONLY, "ru",
                false, java.time.Instant.now());
        assertThat(off.resolvedTranslationMode()).isEqualTo(TranslationMode.ORIGINAL_ONLY);

        LectureLanguage bilingual = new LectureLanguage("l1", LanguageMode.AUTO, "en", null,
                LanguageDetectionSource.WHISPER_DETECTED, TranslationMode.BILINGUAL, "ru",
                false, java.time.Instant.now());
        assertThat(bilingual.resolvedTranslationMode()).isEqualTo(TranslationMode.BILINGUAL);
    }

    @Test
    void unknownLanguageNeverTriggersAutomaticCloudTranslation() {
        LectureLanguage unknown = new LectureLanguage("l1", LanguageMode.AUTO, null, null, null,
                TranslationMode.TRANSLATE_TO_RUSSIAN, "ru", true, java.time.Instant.now());
        assertThat(unknown.resolvedTranslationMode()).isEqualTo(TranslationMode.ORIGINAL_ONLY);
    }
}
