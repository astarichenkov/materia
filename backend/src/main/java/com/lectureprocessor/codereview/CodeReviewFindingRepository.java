package com.lectureprocessor.codereview;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for validated findings belonging to a review job.
 */
@Repository
public class CodeReviewFindingRepository {

    private final JdbcTemplate jdbc;

    public CodeReviewFindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replaceForJob(long jobId, List<ReviewFinding> findings) {
        jdbc.execute("BEGIN");
        try {
            jdbc.update("DELETE FROM code_review_finding WHERE job_id = ?", jobId);
            jdbc.batchUpdate("""
                    INSERT INTO code_review_finding
                        (job_id, category, severity, file_path, line_number, title, description, suggestion,
                         confidence)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,
                    findings.stream().map(f -> new Object[] {
                            jobId, f.category().name(), f.severity().name(), f.file(), f.line(), f.title(),
                            f.description(), f.suggestion(), f.confidence()
                    }).toList());
            jdbc.execute("COMMIT");
        } catch (RuntimeException e) {
            try {
                jdbc.execute("ROLLBACK");
            } catch (RuntimeException ignored) {
                // Already rolled back by the driver.
            }
            throw e;
        }
    }

    public List<ReviewFinding> findByJob(long jobId) {
        return jdbc.query("""
                SELECT category, severity, file_path, line_number, title, description, suggestion, confidence
                FROM code_review_finding WHERE job_id = ? ORDER BY
                    CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                    file_path, line_number
                """, (rs, rowNum) -> new ReviewFinding(
                        ReviewCategory.from(rs.getString("category")),
                        ReviewSeverity.from(rs.getString("severity")),
                        rs.getString("file_path"),
                        rs.getObject("line_number") == null ? null : rs.getInt("line_number"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("suggestion"),
                        rs.getObject("confidence") == null ? null : rs.getDouble("confidence")), jobId);
    }

    public void deleteForJob(long jobId) {
        jdbc.update("DELETE FROM code_review_finding WHERE job_id = ?", jobId);
    }
}
