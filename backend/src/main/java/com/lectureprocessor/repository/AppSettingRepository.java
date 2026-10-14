package com.lectureprocessor.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Simple key/value storage for application settings (e.g. the recommended LLM
 * configuration). Values are stored as text (JSON for structured settings).
 */
@Repository
public class AppSettingRepository {

    public static final String LLM_DEFAULT_CONFIG = "llm.default-config";

    private final JdbcTemplate jdbc;

    public AppSettingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        return jdbc.query("SELECT setting_value FROM app_setting WHERE setting_key = ?",
                (rs, rowNum) -> rs.getString("setting_value"), key).stream().findFirst();
    }

    public void put(String key, String value) {
        jdbc.update("""
                INSERT INTO app_setting (setting_key, setting_value, updated_at)
                VALUES (?,?,?)
                ON CONFLICT(setting_key) DO UPDATE SET
                        setting_value = excluded.setting_value,
                        updated_at = excluded.updated_at
                """, key, value, Instant.now().toString());
    }

    public void delete(String key) {
        jdbc.update("DELETE FROM app_setting WHERE setting_key = ?", key);
    }
}
