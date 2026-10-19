package com.lectureprocessor.processing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.scenario.ProcessingScenario;

/** Persistence for produced document variants. */
@Repository
public class OutputVariantRepository {

    private static final RowMapper<OutputVariant> MAPPER = (ResultSet rs, int rowNum) ->
            new OutputVariant(
                    rs.getLong("id"),
                    rs.getString("lecture_id"),
                    MaterialType.from(rs.getString("material_type")),
                    OutputFormat.from(rs.getString("output_format")),
                    DetailLevel.from(rs.getString("detail_level")),
                    rs.getString("prompt_template"),
                    rs.getString("provider"),
                    rs.getString("model"),
                    rs.getString("notes_markdown"),
                    rs.getString("markdown_artifact"),
                    rs.getString("docx_artifact"),
                    rs.getString("content_hash"),
                    Instant.parse(rs.getString("created_at")),
                    ProcessingScenario.fromId(rs.getString("scenario")));

    private final JdbcTemplate jdbc;

    public OutputVariantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(OutputVariant variant) {
        jdbc.update("""
                INSERT INTO lecture_output_variant (lecture_id, material_type, output_format,
                        detail_level, prompt_template, provider, model, notes_markdown,
                        markdown_artifact, docx_artifact, content_hash, created_at, scenario)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, variant.lectureId(), variant.materialType().name(),
                variant.outputFormat().name(), variant.detailLevel().name(),
                variant.promptTemplate(), variant.provider(), variant.model(),
                variant.notesMarkdown(), variant.markdownArtifact(), variant.docxArtifact(),
                variant.contentHash(), variant.createdAt().toString(),
                variant.scenario() == null ? null : variant.scenario().id());
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id == null ? 0 : id;
    }

    public Optional<OutputVariant> find(long id) {
        return jdbc.query("SELECT * FROM lecture_output_variant WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<OutputVariant> latest(String lectureId) {
        return jdbc.query(
                "SELECT * FROM lecture_output_variant WHERE lecture_id = ? ORDER BY id DESC LIMIT 1",
                MAPPER, lectureId).stream().findFirst();
    }

    public List<OutputVariant> list(String lectureId, int limit) {
        return jdbc.query("""
                SELECT * FROM lecture_output_variant WHERE lecture_id = ?
                ORDER BY id DESC LIMIT ?
                """, MAPPER, lectureId, Math.max(1, limit));
    }

    /** Fills the export columns of the newest variant that has no DOCX yet. */
    public int attachExport(String lectureId, String markdownArtifact, String docxArtifact) {
        return jdbc.update("""
                UPDATE lecture_output_variant
                SET markdown_artifact = ?, docx_artifact = ?
                WHERE id = (SELECT id FROM lecture_output_variant
                            WHERE lecture_id = ? ORDER BY id DESC LIMIT 1)
                  AND (docx_artifact IS NULL OR docx_artifact = '')
                """, markdownArtifact, docxArtifact, lectureId);
    }

    public void updateArtifacts(long id, String markdownArtifact, String docxArtifact) {
        jdbc.update("""
                UPDATE lecture_output_variant
                SET markdown_artifact = COALESCE(?, markdown_artifact),
                    docx_artifact = COALESCE(?, docx_artifact)
                WHERE id = ?
                """, markdownArtifact, docxArtifact, id);
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM lecture_output_variant WHERE lecture_id = ?", lectureId);
    }
}
