package com.lectureprocessor.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.assistant.DocChunk;
import com.lectureprocessor.util.EmbeddingCodec;

/**
 * Persistence for the project-documentation RAG collection. Deliberately
 * separate from {@link ChunkRepository} (lecture transcript chunks).
 */
@Repository
public class DocChunkRepository {

    private final JdbcTemplate jdbc;

    public DocChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Replaces the whole documentation index atomically in one transaction. */
    public void replaceAll(List<DocChunk> chunks, String indexedAt) {
        jdbc.execute("BEGIN");
        try {
            jdbc.update("DELETE FROM doc_chunk");
            jdbc.batchUpdate("""
                    INSERT INTO doc_chunk
                        (source_path, source_title, heading, sequence, text, embedding, embedding_dim, indexed_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,
                    chunks.stream().map(c -> new Object[] {
                            c.sourcePath(), c.sourceTitle(), c.heading(), c.sequence(), c.text(),
                            EmbeddingCodec.encode(c.embedding()),
                            c.embedding() == null ? null : c.embedding().length,
                            indexedAt
                    }).toList());
            jdbc.execute("COMMIT");
        } catch (RuntimeException e) {
            jdbc.execute("ROLLBACK");
            throw e;
        }
    }

    public List<DocChunk> findAll() {
        return jdbc.query("""
                SELECT id, source_path, source_title, heading, sequence, text, embedding
                FROM doc_chunk ORDER BY source_path, sequence
                """,
                (rs, rowNum) -> new DocChunk(
                        rs.getLong("id"),
                        rs.getString("source_path"),
                        rs.getString("source_title"),
                        rs.getString("heading"),
                        rs.getInt("sequence"),
                        rs.getString("text"),
                        EmbeddingCodec.decode(rs.getBytes("embedding"))));
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM doc_chunk", Integer.class);
        return count == null ? 0 : count;
    }

    public int countSources() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT source_path) FROM doc_chunk", Integer.class);
        return count == null ? 0 : count;
    }

    public String lastIndexedAt() {
        List<String> values = jdbc.queryForList("SELECT MAX(indexed_at) FROM doc_chunk", String.class);
        return values.isEmpty() ? null : values.getFirst();
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM doc_chunk");
    }
}
