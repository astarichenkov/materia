package com.lectureprocessor.codereview;

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
 * Persistence for code-review jobs. The idempotency key is enforced by a UNIQUE
 * constraint, so concurrent duplicate webhook deliveries cannot create two runs.
 */
@Repository
public class CodeReviewJobRepository {

    private static final String COLUMNS = """
            repository, pr_number, head_sha, base_sha, config_version, idempotency_key, status,
            triggered_by, provider, model, fallback, cloud, partial, summary, overall_risk, error,
            published_comment_id, findings_count, input_tokens, output_tokens, estimated_cost, cost_currency,
            duration_ms, queue_ms, gitea_ms, retrieval_ms, llm_ms, publish_ms, llm_calls, changed_files,
            analyzed_files, skipped_files, created_at, started_at, finished_at
            """;

    private static final RowMapper<CodeReviewJob> MAPPER = (rs, rowNum) -> new CodeReviewJob(
            rs.getLong("id"),
            rs.getString("repository"),
            rs.getLong("pr_number"),
            rs.getString("head_sha"),
            rs.getString("base_sha"),
            rs.getString("config_version"),
            rs.getString("idempotency_key"),
            rs.getString("status"),
            rs.getString("triggered_by"),
            rs.getString("provider"),
            rs.getString("model"),
            rs.getInt("fallback") == 1,
            rs.getInt("cloud") == 1,
            rs.getInt("partial") == 1,
            rs.getString("summary"),
            rs.getString("overall_risk"),
            rs.getString("error"),
            rs.getString("published_comment_id"),
            rs.getInt("findings_count"),
            nullableInt(rs.getObject("input_tokens")),
            nullableInt(rs.getObject("output_tokens")),
            nullableDouble(rs.getObject("estimated_cost")),
            rs.getString("cost_currency"),
            nullableLong(rs.getObject("duration_ms")),
            nullableLong(rs.getObject("queue_ms")),
            nullableLong(rs.getObject("gitea_ms")),
            nullableLong(rs.getObject("retrieval_ms")),
            nullableLong(rs.getObject("llm_ms")),
            nullableLong(rs.getObject("publish_ms")),
            nullableInt(rs.getObject("llm_calls")),
            nullableInt(rs.getObject("changed_files")),
            nullableInt(rs.getObject("analyzed_files")),
            rs.getString("skipped_files"),
            instant(rs.getString("created_at")),
            instant(rs.getString("started_at")),
            instant(rs.getString("finished_at")));

    private final JdbcTemplate jdbc;

    public CodeReviewJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CodeReviewJob save(CodeReviewJob job) {
        if (job.id() == null) {
            return insert(job);
        }
        update(job);
        return job;
    }

    private CodeReviewJob insert(CodeReviewJob job) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO code_review_job (" + COLUMNS + ") VALUES ("
                            + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            bind(ps, job, true);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? job : job.withId(key.longValue());
    }

    private void update(CodeReviewJob job) {
        jdbc.update("""
                UPDATE code_review_job SET status=?, provider=?, model=?, fallback=?, cloud=?, partial=?,
                    summary=?, overall_risk=?, error=?, published_comment_id=?, findings_count=?,
                    input_tokens=?, output_tokens=?, estimated_cost=?, cost_currency=?, duration_ms=?,
                    queue_ms=?, gitea_ms=?, retrieval_ms=?, llm_ms=?, publish_ms=?, llm_calls=?,
                    changed_files=?, analyzed_files=?, skipped_files=?, started_at=?, finished_at=?
                WHERE id=?
                """,
                job.status(), job.provider(), job.model(), job.fallback() ? 1 : 0, job.cloud() ? 1 : 0,
                job.partial() ? 1 : 0, job.summary(), job.overallRisk(), job.error(), job.publishedCommentId(),
                job.findingsCount(), job.inputTokens(), job.outputTokens(), job.estimatedCost(),
                job.costCurrency(), job.durationMs(), job.queueMs(), job.giteaMs(), job.retrievalMs(),
                job.llmMs(), job.publishMs(), job.llmCalls(), job.changedFiles(), job.analyzedFiles(),
                job.skippedFiles(), iso(job.startedAt()), iso(job.finishedAt()), job.id());
    }

    private void bind(PreparedStatement ps, CodeReviewJob job, boolean insert) throws java.sql.SQLException {
        int i = 1;
        ps.setString(i++, job.repository());
        ps.setLong(i++, job.prNumber());
        ps.setString(i++, job.headSha());
        ps.setString(i++, job.baseSha());
        ps.setString(i++, job.configVersion());
        ps.setString(i++, job.idempotencyKey());
        ps.setString(i++, job.status());
        ps.setString(i++, job.triggeredBy());
        ps.setString(i++, job.provider());
        ps.setString(i++, job.model());
        ps.setInt(i++, job.fallback() ? 1 : 0);
        ps.setInt(i++, job.cloud() ? 1 : 0);
        ps.setInt(i++, job.partial() ? 1 : 0);
        ps.setString(i++, job.summary());
        ps.setString(i++, job.overallRisk());
        ps.setString(i++, job.error());
        ps.setString(i++, job.publishedCommentId());
        ps.setInt(i++, job.findingsCount());
        setInt(ps, i++, job.inputTokens());
        setInt(ps, i++, job.outputTokens());
        setDouble(ps, i++, job.estimatedCost());
        ps.setString(i++, job.costCurrency());
        setLong(ps, i++, job.durationMs());
        setLong(ps, i++, job.queueMs());
        setLong(ps, i++, job.giteaMs());
        setLong(ps, i++, job.retrievalMs());
        setLong(ps, i++, job.llmMs());
        setLong(ps, i++, job.publishMs());
        setInt(ps, i++, job.llmCalls());
        setInt(ps, i++, job.changedFiles());
        setInt(ps, i++, job.analyzedFiles());
        ps.setString(i++, job.skippedFiles());
        ps.setString(i++, iso(job.createdAt()));
        ps.setString(i++, iso(job.startedAt()));
        ps.setString(i, iso(job.finishedAt()));
    }

    public Optional<CodeReviewJob> findById(long id) {
        return jdbc.query("SELECT * FROM code_review_job WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<CodeReviewJob> findByIdempotencyKey(String key) {
        return jdbc.query("SELECT * FROM code_review_job WHERE idempotency_key = ?", MAPPER, key)
                .stream().findFirst();
    }

    public List<CodeReviewJob> findAll(int limit) {
        return jdbc.query("SELECT * FROM code_review_job ORDER BY id DESC LIMIT ?", MAPPER, limit);
    }

    public List<CodeReviewJob> findByRepositoryAndPr(String repository, long prNumber, int limit) {
        return jdbc.query("""
                SELECT * FROM code_review_job WHERE repository = ? AND pr_number = ? ORDER BY id DESC LIMIT ?
                """, MAPPER, repository, prNumber, limit);
    }

    public Optional<CodeReviewJob> findLatestForSha(String repository, long prNumber, String headSha) {
        return jdbc.query("""
                SELECT * FROM code_review_job WHERE repository = ? AND pr_number = ? AND head_sha = ?
                ORDER BY id DESC LIMIT 1
                """, MAPPER, repository, prNumber, headSha).stream().findFirst();
    }

    public boolean existsByIdempotencyKey(String key) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_review_job WHERE idempotency_key = ?", Integer.class, key);
        return count != null && count > 0;
    }

    public long countAll() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM code_review_job", Long.class);
        return count == null ? 0 : count;
    }

    public long countByStatus(String status) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_review_job WHERE status = ?", Long.class, status);
        return count == null ? 0 : count;
    }

    /** Marks non-terminal jobs for the same PR as superseded by a newer commit. */
    public int supersedeRunning(String repository, long prNumber, String keepKey, String reason) {
        return jdbc.update("""
                UPDATE code_review_job SET status = ?, error = ?, finished_at = ?
                WHERE repository = ? AND pr_number = ? AND idempotency_key <> ?
                  AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'SUPERSEDED')
                """, ReviewStatus.SUPERSEDED, reason, Instant.now().toString(), repository, prNumber, keepKey);
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Double nullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
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
