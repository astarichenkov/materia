package com.lectureprocessor.discovery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

/** SQLite cache for provider searches, keyed by provider + query + filters. */
@Repository
public class DiscoveryCacheRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DiscoveryCacheRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<CachedSearch> mapper = (ResultSet rs, int rowNum) ->
            new CachedSearch(rs.getString("provider"), rs.getString("query"),
                    rs.getString("filters"), read(rs.getString("results_json")),
                    rs.getString("status"), rs.getString("error"),
                    Instant.parse(rs.getString("fetched_at")),
                    Instant.parse(rs.getString("expires_at")));

    /** Returns a non-expired cached search. */
    public Optional<CachedSearch> findFresh(String provider, String query, String filters) {
        return jdbc.query("""
                SELECT * FROM video_discovery_cache
                WHERE provider = ? AND query = ? AND filters = ? AND expires_at > ?
                """, mapper, provider, query, filters, Instant.now().toString())
                .stream().findFirst();
    }

    public void store(String provider, String query, String filters,
            VideoSearchProvider.SearchOutcome outcome, Instant expiresAt) {
        try {
            String json = objectMapper.writeValueAsString(outcome.videos());
            jdbc.update("""
                    INSERT INTO video_discovery_cache (provider, query, filters, results_json,
                            status, error, fetched_at, expires_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    ON CONFLICT(provider, query, filters) DO UPDATE SET
                            results_json = excluded.results_json,
                            status = excluded.status,
                            error = excluded.error,
                            fetched_at = excluded.fetched_at,
                            expires_at = excluded.expires_at
                    """, provider, query, filters, json,
                    outcome.success() ? "OK" : "ERROR", outcome.error(),
                    Instant.now().toString(), expiresAt.toString());
        } catch (RuntimeException e) {
            // A cache write must never fail a user search.
        }
    }

    public void deleteExpired() {
        jdbc.update("DELETE FROM video_discovery_cache WHERE expires_at <= ?",
                Instant.now().toString());
    }

    private List<DiscoveredVideo> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            DiscoveredVideo[] videos = objectMapper.readValue(json, DiscoveredVideo[].class);
            return videos == null ? List.of() : List.of(videos);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public record CachedSearch(String provider, String query, String filters,
            List<DiscoveredVideo> videos, String status, String error, Instant fetchedAt,
            Instant expiresAt) {

        public VideoSearchProvider.SearchOutcome toOutcome(String method) {
            return new VideoSearchProvider.SearchOutcome(videos, method, error);
        }
    }
}
