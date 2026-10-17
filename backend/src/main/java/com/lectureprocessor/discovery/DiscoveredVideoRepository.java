package com.lectureprocessor.discovery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DiscoveredVideoRepository {

    private final JdbcTemplate jdbc;

    public DiscoveredVideoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<DiscoveredVideo> mapper = (ResultSet rs, int rowNum) ->
            new DiscoveredVideo(rs.getLong("id"), rs.getString("provider"),
                    rs.getString("external_id"), rs.getString("canonical_url"),
                    rs.getString("title"), rs.getString("description"), rs.getString("channel"),
                    rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
                    rs.getString("language"),
                    rs.getString("published_at") == null ? null
                            : Instant.parse(rs.getString("published_at")),
                    rs.getString("thumbnail_url"), rs.getString("query"),
                    rs.getObject("relevance") == null ? null : rs.getDouble("relevance"),
                    rs.getString("relevance_basis"), rs.getString("imported_lecture_id"));

    /** Idempotent upsert by (provider, external_id) with canonical URL fallback. */
    public DiscoveredVideo upsert(DiscoveredVideo video) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO discovered_video (provider, external_id, canonical_url, title,
                        description, channel, duration_ms, language, published_at, thumbnail_url,
                        query, relevance, relevance_basis, imported_lecture_id,
                        first_seen_at, last_seen_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(provider, external_id) DO UPDATE SET
                        canonical_url = excluded.canonical_url,
                        title = excluded.title,
                        description = excluded.description,
                        channel = excluded.channel,
                        duration_ms = excluded.duration_ms,
                        language = excluded.language,
                        published_at = excluded.published_at,
                        thumbnail_url = excluded.thumbnail_url,
                        query = excluded.query,
                        relevance = excluded.relevance,
                        relevance_basis = excluded.relevance_basis,
                        last_seen_at = excluded.last_seen_at
                """, video.provider(), video.externalId(), video.canonicalUrl(), video.title(),
                video.description(), video.channel(), video.durationMs(), video.language(),
                video.publishedAt() == null ? null : video.publishedAt().toString(),
                video.thumbnailUrl(), video.query(), video.relevance(), video.relevanceBasis(),
                video.importedLectureId(), now.toString(), now.toString());
        return findByProviderAndExternalId(video.provider(), video.externalId())
                .or(() -> findByUrl(video.canonicalUrl())).orElse(video);
    }

    public Optional<DiscoveredVideo> findByProviderAndExternalId(String provider,
            String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT * FROM discovered_video WHERE provider = ? AND external_id = ?
                """, mapper, provider, externalId).stream().findFirst();
    }

    public Optional<DiscoveredVideo> findByUrl(String canonicalUrl) {
        return jdbc.query("SELECT * FROM discovered_video WHERE canonical_url = ?", mapper,
                canonicalUrl).stream().findFirst();
    }

    public List<DiscoveredVideo> findRecent(int limit) {
        return jdbc.query("""
                SELECT * FROM discovered_video ORDER BY last_seen_at DESC LIMIT ?
                """, mapper, limit);
    }

    public void markImported(String provider, String externalId,
            String lectureId) {
        jdbc.update("""
                UPDATE discovered_video SET imported_lecture_id = ?, last_seen_at = ?
                WHERE provider = ? AND external_id = ?
                """, lectureId, Instant.now().toString(), provider, externalId);
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM discovered_video WHERE imported_lecture_id = ?", lectureId);
    }
}
