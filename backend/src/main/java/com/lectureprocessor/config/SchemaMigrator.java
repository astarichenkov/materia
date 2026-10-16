package com.lectureprocessor.config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * Minimal, explicit versioned migration runner. The base schema is created by
 * {@code db/schema.sql} (idempotent {@code CREATE TABLE IF NOT EXISTS}) and this
 * runner applies additive numbered migrations that evolve an existing database
 * without touching existing rows.
 *
 * <p>Version 1 represents the pre-Week-7 base schema; this class applies version
 * 2 (ingestion tables). Migration scripts are SQL resources under
 * {@code db/migration/}.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private record Migration(int version, String resource) {
    }

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(2, "db/migration/V2__week7_ingestion.sql"),
            new Migration(3, "db/migration/V3__day31_developer_assistant.sql"),
            new Migration(4, "db/migration/V4__day32_cloud_provider.sql"),
            new Migration(5, "db/migration/V5__day32_code_review.sql"));

    private final JdbcTemplate jdbc;

    public SchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS schema_migration (
                    version    INTEGER PRIMARY KEY,
                    applied_at TEXT NOT NULL
                )
                """);
        for (Migration migration : MIGRATIONS) {
            if (isApplied(migration.version())) {
                continue;
            }
            log.info("Applying DB migration V{} from {}", migration.version(), migration.resource());
            ResourceDatabasePopulator populator =
                    new ResourceDatabasePopulator(new ClassPathResource(migration.resource()));
            populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
            populator.execute(jdbc.getDataSource());
            jdbc.update("INSERT INTO schema_migration (version, applied_at) VALUES (?, ?)",
                    migration.version(), java.time.Instant.now().toString());
            log.info("DB migration V{} applied", migration.version());
        }
    }

    private boolean isApplied(int version) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version = ?", Long.class, version);
        return count != null && count > 0;
    }
}
