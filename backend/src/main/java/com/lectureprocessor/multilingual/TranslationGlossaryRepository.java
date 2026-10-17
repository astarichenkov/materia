package com.lectureprocessor.multilingual;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Persistence for the editable translation glossary. */
@Repository
public class TranslationGlossaryRepository {

    private final JdbcTemplate jdbc;

    public TranslationGlossaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<GlossaryEntry> MAPPER = (ResultSet rs, int rowNum) ->
            new GlossaryEntry(rs.getLong("id"), rs.getString("term"),
                    rs.getString("translation"), rs.getString("source_language"),
                    rs.getString("target_language"), rs.getInt("case_sensitive") == 1,
                    rs.getInt("enabled") == 1, rs.getInt("is_builtin") == 1,
                    Instant.parse(rs.getString("updated_at")));

    public List<GlossaryEntry> findAll() {
        return jdbc.query("SELECT * FROM translation_glossary ORDER BY is_builtin DESC, term", MAPPER);
    }

    public List<GlossaryEntry> findEnabled(String sourceLanguage, String targetLanguage) {
        return jdbc.query("""
                SELECT * FROM translation_glossary
                WHERE enabled = 1 AND source_language = ? AND target_language = ?
                ORDER BY term
                """, MAPPER, sourceLanguage, targetLanguage);
    }

    public Optional<GlossaryEntry> find(long id) {
        return jdbc.query("SELECT * FROM translation_glossary WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public GlossaryEntry save(GlossaryEntry entry) {
        if (entry.id() > 0) {
            jdbc.update("""
                    UPDATE translation_glossary SET term = ?, translation = ?, source_language = ?,
                            target_language = ?, case_sensitive = ?, enabled = ?, updated_at = ?
                    WHERE id = ?
                    """, entry.term(), entry.translation(), entry.sourceLanguage(),
                    entry.targetLanguage(), entry.caseSensitive() ? 1 : 0, entry.enabled() ? 1 : 0,
                    Instant.now().toString(), entry.id());
            return find(entry.id()).orElseThrow();
        }
        jdbc.update("""
                INSERT INTO translation_glossary (term, translation, source_language, target_language,
                        case_sensitive, enabled, is_builtin, updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT(term, source_language, target_language) DO UPDATE SET
                        translation = excluded.translation,
                        case_sensitive = excluded.case_sensitive,
                        enabled = excluded.enabled,
                        updated_at = excluded.updated_at
                """, entry.term(), entry.translation(), entry.sourceLanguage(),
                entry.targetLanguage(), entry.caseSensitive() ? 1 : 0, entry.enabled() ? 1 : 0,
                entry.builtin() ? 1 : 0, Instant.now().toString());
        return jdbc.query("""
                SELECT * FROM translation_glossary
                WHERE term = ? AND source_language = ? AND target_language = ?
                """, MAPPER, entry.term(), entry.sourceLanguage(), entry.targetLanguage())
                .stream().findFirst().orElseThrow();
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM translation_glossary WHERE id = ? AND is_builtin = 0", id) > 0;
    }

    public void setEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE translation_glossary SET enabled = ?, updated_at = ? WHERE id = ?",
                enabled ? 1 : 0, Instant.now().toString(), id);
    }
}
