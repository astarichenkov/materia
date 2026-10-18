package com.lectureprocessor.processing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Persisted per-lecture output profile (one row per lecture). */
@Repository
public class ProcessingProfileRepository {

    private static final RowMapper<ProcessingProfile> MAPPER = (ResultSet rs, int rowNum) ->
            ProcessingProfile.explicit(
                    MaterialType.from(rs.getString("material_type")),
                    OutputFormat.from(rs.getString("output_format")),
                    DetailLevel.from(rs.getString("detail_level")),
                    rs.getString("prompt_template"));

    private final JdbcTemplate jdbc;

    public ProcessingProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProcessingProfile> find(String lectureId) {
        return jdbc.query("SELECT * FROM lecture_processing_profile WHERE lecture_id = ?", MAPPER,
                lectureId).stream().findFirst();
    }

    public void save(String lectureId, ProcessingProfile profile) {
        jdbc.update("""
                INSERT INTO lecture_processing_profile (lecture_id, material_type, output_format,
                        detail_level, prompt_template, updated_at)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT(lecture_id) DO UPDATE SET
                        material_type = excluded.material_type,
                        output_format = excluded.output_format,
                        detail_level = excluded.detail_level,
                        prompt_template = excluded.prompt_template,
                        updated_at = excluded.updated_at
                """, lectureId, profile.materialType().name(), profile.outputFormat().name(),
                profile.detailLevel().name(), profile.promptTemplate(), Instant.now().toString());
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM lecture_processing_profile WHERE lecture_id = ?", lectureId);
    }
}
