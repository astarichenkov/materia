package com.lectureprocessor.benchmark;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Persists benchmark runs so history, comparison and the recommended
 * configuration survive restarts. All measured values are stored as-is; unknown
 * metrics stay {@code null}.
 */
@Repository
public class BenchmarkRunRepository {

    private static final String COLUMNS = """
            lecture_id, provider, model, temperature, max_tokens, context_window, thinking,
            prompt_template, quantization, input_tokens, output_tokens, thinking_tokens,
            llm_calls, chunk_count, llm_duration_ms, total_duration_ms, tokens_per_second,
            peak_ram_mb, peak_vram_mb, quality_score, quality_notes, status, error, output,
            recommended, created_at
            """;

    private static final RowMapper<BenchmarkRun> MAPPER = (rs, rowNum) -> new BenchmarkRun(
            rs.getLong("id"),
            rs.getString("lecture_id"),
            rs.getString("provider"),
            rs.getString("model"),
            nullableDouble(rs.getObject("temperature")),
            nullableInt(rs.getObject("max_tokens")),
            nullableInt(rs.getObject("context_window")),
            nullableBoolean(rs.getObject("thinking")),
            rs.getString("prompt_template"),
            rs.getString("quantization"),
            nullableInt(rs.getObject("input_tokens")),
            nullableInt(rs.getObject("output_tokens")),
            nullableInt(rs.getObject("thinking_tokens")),
            nullableInt(rs.getObject("llm_calls")),
            nullableInt(rs.getObject("chunk_count")),
            nullableLong(rs.getObject("llm_duration_ms")),
            nullableLong(rs.getObject("total_duration_ms")),
            nullableDouble(rs.getObject("tokens_per_second")),
            nullableLong(rs.getObject("peak_ram_mb")),
            nullableLong(rs.getObject("peak_vram_mb")),
            nullableDouble(rs.getObject("quality_score")),
            rs.getString("quality_notes"),
            rs.getString("status"),
            rs.getString("error"),
            rs.getString("output"),
            rs.getInt("recommended") == 1,
            Instant.parse(rs.getString("created_at")));

    private final JdbcTemplate jdbc;

    public BenchmarkRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BenchmarkRun save(BenchmarkRun run) {
        if (run.id() == null) {
            return insert(run);
        }
        jdbc.update("""
                UPDATE benchmark_run SET status=?, input_tokens=?, output_tokens=?, thinking_tokens=?,
                        llm_calls=?, chunk_count=?, llm_duration_ms=?, total_duration_ms=?,
                        tokens_per_second=?, peak_ram_mb=?, peak_vram_mb=?, quality_score=?,
                        quality_notes=?, error=?, output=?, recommended=?
                WHERE id=?
                """,
                run.status(), run.inputTokens(), run.outputTokens(), run.thinkingTokens(),
                run.llmCalls(), run.chunkCount(), run.llmDurationMs(), run.totalDurationMs(),
                run.tokensPerSecond(), run.peakRamMb(), run.peakVramMb(), run.qualityScore(),
                run.qualityNotes(), run.error(), run.output(), run.recommended() ? 1 : 0, run.id());
        return run;
    }

    private BenchmarkRun insert(BenchmarkRun run) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO benchmark_run (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, run.lectureId());
            ps.setString(i++, run.provider());
            ps.setString(i++, run.model());
            setDouble(ps, i++, run.temperature());
            setInt(ps, i++, run.maxTokens());
            setInt(ps, i++, run.contextWindow());
            if (run.thinking() == null) {
                ps.setObject(i++, null);
            } else {
                ps.setInt(i++, run.thinking() ? 1 : 0);
            }
            ps.setString(i++, run.promptTemplate());
            ps.setString(i++, run.quantization());
            setInt(ps, i++, run.inputTokens());
            setInt(ps, i++, run.outputTokens());
            setInt(ps, i++, run.thinkingTokens());
            setInt(ps, i++, run.llmCalls());
            setInt(ps, i++, run.chunkCount());
            setLong(ps, i++, run.llmDurationMs());
            setLong(ps, i++, run.totalDurationMs());
            setDouble(ps, i++, run.tokensPerSecond());
            setLong(ps, i++, run.peakRamMb());
            setLong(ps, i++, run.peakVramMb());
            setDouble(ps, i++, run.qualityScore());
            ps.setString(i++, run.qualityNotes());
            ps.setString(i++, run.status());
            ps.setString(i++, run.error());
            ps.setString(i++, run.output());
            ps.setInt(i++, run.recommended() ? 1 : 0);
            ps.setString(i, run.createdAt().toString());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? run : run.withId(key.longValue());
    }

    public Optional<BenchmarkRun> findById(long id) {
        return jdbc.query("SELECT * FROM benchmark_run WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<BenchmarkRun> findAll(int limit) {
        return jdbc.query("SELECT * FROM benchmark_run ORDER BY id DESC LIMIT ?", MAPPER, limit);
    }

    public List<BenchmarkRun> findByLecture(String lectureId, int limit) {
        return jdbc.query("SELECT * FROM benchmark_run WHERE lecture_id = ? ORDER BY id DESC LIMIT ?",
                MAPPER, lectureId, limit);
    }

    public void clearRecommended() {
        jdbc.update("UPDATE benchmark_run SET recommended = 0 WHERE recommended = 1");
    }

    public void markRecommended(long id) {
        clearRecommended();
        jdbc.update("UPDATE benchmark_run SET recommended = 1 WHERE id = ?", id);
    }

    public Optional<BenchmarkRun> findRecommended() {
        return jdbc.query("SELECT * FROM benchmark_run WHERE recommended = 1 ORDER BY id DESC LIMIT 1",
                MAPPER).stream().findFirst();
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM benchmark_run WHERE id = ?", id);
    }

    private static Double nullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Boolean nullableBoolean(Object value) {
        return value == null ? null : ((Number) value).intValue() != 0;
    }

    private static void setInt(PreparedStatement ps, int index, Integer value) throws java.sql.SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setInt(index, value);
        }
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setDouble(PreparedStatement ps, int index, Double value) throws java.sql.SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setDouble(index, value);
        }
    }
}
