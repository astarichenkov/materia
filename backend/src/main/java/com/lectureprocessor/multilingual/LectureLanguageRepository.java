package com.lectureprocessor.multilingual;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Persists per-lecture language preferences and detection results. */
@Repository
public class LectureLanguageRepository {

    private final JdbcTemplate jdbc;

    public LectureLanguageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LectureLanguage> MAPPER = (ResultSet rs, int rowNum) ->
            new LectureLanguage(
                    rs.getString("lecture_id"),
                    LanguageMode.from(rs.getString("language_mode")),
                    rs.getString("detected_language"),
                    rs.getObject("language_confidence") == null ? null
                            : rs.getDouble("language_confidence"),
                    rs.getString("detection_source") == null ? null
                            : LanguageDetectionSource.valueOf(rs.getString("detection_source")),
                    TranslationMode.from(rs.getString("translation_mode")),
                    rs.getString("target_language"),
                    rs.getInt("auto_translate") == 1,
                    Instant.parse(rs.getString("updated_at")));

    public Optional<LectureLanguage> find(String lectureId) {
        List<LectureLanguage> rows = jdbc.query(
                "SELECT * FROM lecture_language WHERE lecture_id = ?", MAPPER, lectureId);
        return rows.stream().findFirst();
    }

    public LectureLanguage findOrDefault(String lectureId, MultilingualProperties properties) {
        return find(lectureId).orElseGet(() -> LectureLanguage.defaults(lectureId,
                properties.defaultLanguageMode(), properties.defaultTranslationMode(),
                properties.targetLanguage(), properties.autoTranslate()));
    }

    public void save(LectureLanguage language) {
        jdbc.update("""
                INSERT INTO lecture_language (lecture_id, language_mode, detected_language,
                        language_confidence, detection_source, translation_mode, target_language,
                        auto_translate, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(lecture_id) DO UPDATE SET
                        language_mode = excluded.language_mode,
                        detected_language = excluded.detected_language,
                        language_confidence = excluded.language_confidence,
                        detection_source = excluded.detection_source,
                        translation_mode = excluded.translation_mode,
                        target_language = excluded.target_language,
                        auto_translate = excluded.auto_translate,
                        updated_at = excluded.updated_at
                """,
                language.lectureId(), language.languageMode().name(), language.detectedLanguage(),
                language.languageConfidence(), language.detectionSource() == null ? null
                        : language.detectionSource().name(),
                language.translationMode().name(), language.targetLanguage(),
                language.autoTranslate() ? 1 : 0, language.updatedAt().toString());
    }

    public void delete(String lectureId) {
        jdbc.update("DELETE FROM lecture_language WHERE lecture_id = ?", lectureId);
    }
}
