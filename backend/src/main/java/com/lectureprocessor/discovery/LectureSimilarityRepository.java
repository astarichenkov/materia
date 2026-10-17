package com.lectureprocessor.discovery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class LectureSimilarityRepository {

    private final JdbcTemplate jdbc;

    public LectureSimilarityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<LectureSimilarity> mapper = (ResultSet rs, int rowNum) ->
            new LectureSimilarity(rs.getString("lecture_id"),
                    rs.getString("similar_lecture_id"), rs.getDouble("score"),
                    rs.getString("basis"), split(rs.getString("common_topics")),
                    Instant.parse(rs.getString("computed_at")));

    public void replaceForLecture(String lectureId, List<LectureSimilarity> similarities) {
        jdbc.update("DELETE FROM lecture_similarity WHERE lecture_id = ?", lectureId);
        if (similarities.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                        INSERT INTO lecture_similarity (lecture_id, similar_lecture_id, score, basis,
                                common_topics, computed_at)
                        VALUES (?,?,?,?,?,?)
                        """,
                similarities.stream().map(s -> new Object[] {
                        s.lectureId(), s.similarLectureId(), s.score(), s.basis(),
                        String.join("\n", s.commonTopics()), s.computedAt().toString()
                }).toList());
    }

    public List<LectureSimilarity> findByLecture(String lectureId) {
        return jdbc.query("""
                SELECT * FROM lecture_similarity WHERE lecture_id = ? ORDER BY score DESC
                """, mapper, lectureId);
    }

    public void deleteByLecture(String lectureId) {
        jdbc.update("DELETE FROM lecture_similarity WHERE lecture_id = ? OR similar_lecture_id = ?",
                lectureId, lectureId);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\R")).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
    }
}
