package com.lectureprocessor.discovery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.util.EmbeddingCodec;

@Repository
public class LectureTopicRepository {

    private final JdbcTemplate jdbc;

    public LectureTopicRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<LectureTopic> mapper = (ResultSet rs, int rowNum) -> new LectureTopic(
            rs.getString("lecture_id"), rs.getString("title"), rs.getString("summary"),
            split(rs.getString("key_topics")), split(rs.getString("keywords")),
            rs.getString("profile_text"), EmbeddingCodec.decode(rs.getBytes("embedding")),
            rs.getString("source_hash"), Instant.parse(rs.getString("updated_at")));

    public Optional<LectureTopic> find(String lectureId) {
        return jdbc.query("SELECT * FROM lecture_topic WHERE lecture_id = ?", mapper, lectureId)
                .stream().findFirst();
    }

    public List<LectureTopic> findAll() {
        return jdbc.query("SELECT * FROM lecture_topic", mapper);
    }

    public void save(LectureTopic topic) {
        jdbc.update("""
                INSERT INTO lecture_topic (lecture_id, title, summary, key_topics, keywords,
                        profile_text, embedding, embedding_dim, source_hash, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(lecture_id) DO UPDATE SET
                        title = excluded.title,
                        summary = excluded.summary,
                        key_topics = excluded.key_topics,
                        keywords = excluded.keywords,
                        profile_text = excluded.profile_text,
                        embedding = excluded.embedding,
                        embedding_dim = excluded.embedding_dim,
                        source_hash = excluded.source_hash,
                        updated_at = excluded.updated_at
                """, topic.lectureId(), topic.title(), topic.summary(),
                String.join("\n", topic.keyTopics()), String.join("\n", topic.keywords()),
                topic.profileText(), EmbeddingCodec.encode(topic.embedding()),
                topic.embedding() == null ? null : topic.embedding().length,
                topic.sourceHash(), topic.updatedAt().toString());
    }

    public void delete(String lectureId) {
        jdbc.update("DELETE FROM lecture_topic WHERE lecture_id = ?", lectureId);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\R")).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
    }
}
