package com.lectureprocessor.multilingual;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

/** Persistence for resumable segment translations. */
@Repository
public class TranslationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TranslationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<LectureTranslation> translationMapper = (ResultSet rs, int rowNum) ->
            new LectureTranslation(
                    rs.getLong("id"), rs.getString("lecture_id"),
                    rs.getString("source_fingerprint"), rs.getString("source_language"),
                    rs.getString("target_language"), rs.getString("provider"),
                    rs.getString("model"), rs.getString("prompt_version"),
                    rs.getString("glossary_version"), rs.getString("settings_hash"),
                    TranslationStatus.valueOf(rs.getString("status")),
                    rs.getInt("segments_total"), rs.getInt("segments_translated"),
                    rs.getInt("segments_failed"), readWarnings(rs.getString("warnings_json")),
                    rs.getString("error"),
                    rs.getObject("input_tokens") == null ? null : rs.getInt("input_tokens"),
                    rs.getObject("output_tokens") == null ? null : rs.getInt("output_tokens"),
                    rs.getInt("llm_calls"),
                    rs.getObject("estimated_cost") == null ? null : rs.getDouble("estimated_cost"),
                    rs.getString("cost_currency"),
                    Instant.parse(rs.getString("created_at")),
                    Instant.parse(rs.getString("updated_at")));

    private final RowMapper<TranslationSegment> segmentMapper = (ResultSet rs, int rowNum) ->
            new TranslationSegment(
                    rs.getInt("sequence"), rs.getLong("start_ms"), rs.getLong("end_ms"),
                    rs.getString("source_text"), rs.getString("translated_text"),
                    SegmentTranslationStatus.valueOf(rs.getString("status")),
                    rs.getString("quality"), rs.getString("warning"), rs.getString("error"),
                    rs.getInt("attempts"));

    public Optional<LectureTranslation> find(String lectureId, String sourceFingerprint,
            String settingsHash) {
        return jdbc.query("""
                SELECT * FROM lecture_translation
                WHERE lecture_id = ? AND source_fingerprint = ? AND settings_hash = ?
                """, translationMapper, lectureId, sourceFingerprint, settingsHash)
                .stream().findFirst();
    }

    /** Latest translation regardless of fingerprint (for UI and export fallback). */
    public Optional<LectureTranslation> findLatest(String lectureId) {
        return jdbc.query("""
                SELECT * FROM lecture_translation WHERE lecture_id = ? ORDER BY id DESC LIMIT 1
                """, translationMapper, lectureId).stream().findFirst();
    }

    public LectureTranslation createOrGet(String lectureId, String sourceFingerprint,
            String sourceLanguage, String targetLanguage, String provider, String model,
            String promptVersion, String glossaryVersion, String settingsHash, int totalSegments) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO lecture_translation (lecture_id, source_fingerprint, source_language,
                        target_language, provider, model, prompt_version, glossary_version,
                        settings_hash, status, segments_total, segments_translated, segments_failed,
                        llm_calls, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,0,0,0,?,?)
                ON CONFLICT(lecture_id, source_fingerprint, settings_hash) DO NOTHING
                """, lectureId, sourceFingerprint, sourceLanguage, targetLanguage, provider, model,
                promptVersion, glossaryVersion, settingsHash, TranslationStatus.PENDING.name(),
                totalSegments, now.toString(), now.toString());
        return find(lectureId, sourceFingerprint, settingsHash).orElseThrow();
    }

    public List<TranslationSegment> segments(long translationId) {
        return jdbc.query("""
                SELECT * FROM translation_segment WHERE translation_id = ? ORDER BY sequence
                """, segmentMapper, translationId);
    }

    public void upsertSegments(long translationId, List<TranslationSegment> segments) {
        if (segments.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                        INSERT INTO translation_segment (translation_id, sequence, start_ms, end_ms,
                                source_text, translated_text, status, quality, warning, error, attempts)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(translation_id, sequence) DO UPDATE SET
                                start_ms = excluded.start_ms,
                                end_ms = excluded.end_ms,
                                source_text = excluded.source_text,
                                translated_text = excluded.translated_text,
                                status = excluded.status,
                                quality = excluded.quality,
                                warning = excluded.warning,
                                error = excluded.error,
                                attempts = excluded.attempts
                        """,
                segments.stream().map(s -> new Object[] {
                        translationId, s.sequence(), s.startMs(), s.endMs(), s.sourceText(),
                        s.translatedText(), s.status().name(), s.quality(), s.warning(), s.error(),
                        s.attempts()
                }).toList());
    }

    public void updateStatus(LectureTranslation translation) {
        jdbc.update("""
                UPDATE lecture_translation SET status = ?, segments_total = ?,
                        segments_translated = ?, segments_failed = ?, warnings_json = ?, error = ?,
                        input_tokens = ?, output_tokens = ?, llm_calls = ?, estimated_cost = ?,
                        cost_currency = ?, updated_at = ?
                WHERE id = ?
                """, translation.status().name(), translation.segmentsTotal(),
                translation.segmentsTranslated(), translation.segmentsFailed(),
                writeWarnings(translation.warnings()), translation.error(),
                translation.inputTokens(), translation.outputTokens(), translation.llmCalls(),
                translation.estimatedCost(), translation.costCurrency(),
                Instant.now().toString(), translation.id());
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("""
                DELETE FROM translation_segment WHERE translation_id IN
                (SELECT id FROM lecture_translation WHERE lecture_id = ?)
                """, lectureId);
        jdbc.update("DELETE FROM lecture_translation WHERE lecture_id = ?", lectureId);
    }

    /** Completed translations for a lecture, newest first. */
    public List<LectureTranslation> findAll(String lectureId) {
        return jdbc.query("""
                SELECT * FROM lecture_translation WHERE lecture_id = ? ORDER BY id DESC
                """, translationMapper, lectureId);
    }

    private List<String> readWarnings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            String[] parsed = objectMapper.readValue(json, String[].class);
            return parsed == null ? List.of() : List.of(parsed);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private String writeWarnings(List<String> warnings) {
        try {
            return objectMapper.writeValueAsString(warnings == null ? List.of() : warnings);
        } catch (RuntimeException e) {
            return "[]";
        }
    }
}
