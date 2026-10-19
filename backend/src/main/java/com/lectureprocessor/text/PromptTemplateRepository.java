package com.lectureprocessor.text;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Persistence of user-owned prompt templates. Built-in prompts stay on the
 * classpath and are never written here; a user copy is a separate row.
 */
@Repository
public class PromptTemplateRepository {

    private final JdbcTemplate jdbc;

    public PromptTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StoredTemplate(
            String id,
            String name,
            String description,
            String sourceTemplate,
            String body,
            int version,
            String materialTypes,
            String outputFormats,
            boolean builtIn,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }

    private static final RowMapper<StoredTemplate> MAPPER = (ResultSet rs, int rowNum) ->
            new StoredTemplate(
                    rs.getString("id"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("source_template"),
                    rs.getString("body"),
                    rs.getInt("version"),
                    rs.getString("material_types"),
                    rs.getString("output_formats"),
                    rs.getInt("built_in") == 1,
                    rs.getString("status"),
                    Instant.parse(rs.getString("created_at")),
                    Instant.parse(rs.getString("updated_at")));

    public List<StoredTemplate> findAll() {
        return jdbc.query("SELECT * FROM prompt_template ORDER BY updated_at DESC", MAPPER);
    }

    public Optional<StoredTemplate> find(String id) {
        return jdbc.query("SELECT * FROM prompt_template WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<StoredTemplate> findByName(String name) {
        return jdbc.query("SELECT * FROM prompt_template WHERE name = ? ORDER BY updated_at DESC LIMIT 1",
                MAPPER, name).stream().findFirst();
    }

    public void insert(String id, String name, String description, String sourceTemplate,
            String body, String materialTypes, String outputFormats) {
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO prompt_template (id, name, description, source_template, body, version,
                        material_types, output_formats, built_in, status, created_at, updated_at)
                VALUES (?,?,?,?,?,1,?,?,0,'ACTIVE',?,?)
                """, id, name, description, sourceTemplate, body, materialTypes, outputFormats,
                now, now);
    }

    public void update(String id, String name, String description, String body) {
        jdbc.update("""
                UPDATE prompt_template
                SET name = ?, description = ?, body = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND built_in = 0
                """, name, description, body, Instant.now().toString(), id);
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM prompt_template WHERE id = ? AND built_in = 0", id);
    }

    public void deleteByLecturePlaceholder() {
        // Reserved for future per-lecture template references.
    }
}
