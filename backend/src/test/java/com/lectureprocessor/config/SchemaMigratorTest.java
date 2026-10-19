package com.lectureprocessor.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Verifies that the versioned migration upgrades a pre-Week-7 database in place,
 * preserving existing lecture rows.
 */
class SchemaMigratorTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesExistingDatabaseWithoutDataLoss() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Simulate the legacy (version 1) schema with one lecture row.
        jdbc.execute("""
                CREATE TABLE lecture (
                    id TEXT PRIMARY KEY,
                    original_filename TEXT NOT NULL,
                    stored_filename TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    content_type TEXT,
                    status TEXT NOT NULL,
                    error_message TEXT,
                    duration_ms INTEGER,
                    clean_transcript INTEGER NOT NULL DEFAULT 0,
                    extract_slides INTEGER NOT NULL DEFAULT 0,
                    raw_transcript_path TEXT,
                    cleaned_text TEXT,
                    notes_markdown TEXT,
                    docx_path TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO lecture (id, original_filename, stored_filename, size_bytes, status,
                        clean_transcript, extract_slides, created_at, updated_at)
                VALUES ('legacy-1','old.mp4','source.mp4',100,'COMPLETED',0,0,?,?)
                """, now, now);

        new SchemaMigrator(jdbc).run(null);

        // Legacy row preserved.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lecture", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT original_filename FROM lecture WHERE id = 'legacy-1'", String.class))
                .isEqualTo("old.mp4");

        // New tables created and version recorded.
        for (String table : List.of("media_source", "pipeline_job", "stage_checkpoint",
                "download_state", "doc_chunk", "schema_migration", "provider_credential",
                "support_ticket", "support_message", "support_tool_call",
                "file_agent_task", "file_agent_execution", "file_agent_tool_call",
                "file_change_proposal", "proposed_file_change",
                "lecture_language", "lecture_translation", "translation_segment",
                "translation_glossary", "lecture_topic", "lecture_similarity",
                "video_discovery_cache", "discovered_video",
                "lecture_document_revision", "lecture_segment_correction",
                "lecture_document_state", "lecture_export",
                "lecture_thumbnail", "lecture_processing_profile", "lecture_output_variant",
                "pipeline_run", "prompt_template")) {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Long.class, table);
            assertThat(count).as("table %s", table).isEqualTo(1L);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version = 2", Long.class)).isEqualTo(1L);
        // Day 31 documentation RAG table and migration version.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version = 3", Long.class)).isEqualTo(1L);
        // Day 32 cloud provider credentials migration.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version = 4", Long.class)).isEqualTo(1L);
        // Day 32 code review migration.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version = 5", Long.class)).isEqualTo(1L);
        // Day 33 support assistant migration.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version = 6", Long.class)).isEqualTo(1L);
        // Day 34 file agent migration.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version = 7", Long.class)).isEqualTo(1L);
        // Week 8 multilingual + discovery migration.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version = 8", Long.class)).isEqualTo(1L);
        // Day 35 lecture editor migration.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version = 9", Long.class)).isEqualTo(1L);
        // Nightly roadmap: thumbnails, processing profiles, output variants.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version = 10", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version = 11", Long.class)).isEqualTo(1L);
        // Materia scenarios: nullable scenario columns preserve legacy rows.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version = 12", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForList("PRAGMA table_info(lecture_processing_profile)").stream()
                .map(row -> row.get("name")))
                .contains("scenario");
        assertThat(jdbc.queryForList("PRAGMA table_info(lecture_output_variant)").stream()
                .map(row -> row.get("name")))
                .contains("scenario");

        // Running the migrator again is idempotent.
        new SchemaMigrator(jdbc).run(null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_migration", Long.class)).isEqualTo(11L);
        assertThat(Files.exists(db)).isTrue();
    }
}
