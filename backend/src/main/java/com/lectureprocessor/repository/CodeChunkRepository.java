package com.lectureprocessor.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.codereview.code.CodeChunk;
import com.lectureprocessor.util.EmbeddingCodec;

/**
 * Persistence for the source-code RAG collection, versioned by commit SHA so a
 * review always retrieves code from the exact revision under review.
 */
@Repository
public class CodeChunkRepository {

    private final JdbcTemplate jdbc;

    public CodeChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Atomically replaces the index for one commit. */
    public void replaceForCommit(String repository, String commitSha, String gitRef, List<CodeChunk> chunks,
            String indexedAt) {
        jdbc.execute("BEGIN");
        try {
            jdbc.update("DELETE FROM code_chunk WHERE repository = ? AND commit_sha = ?", repository, commitSha);
            jdbc.batchUpdate("""
                    INSERT INTO code_chunk
                        (repository, git_ref, commit_sha, file_path, chunk_type, symbol, start_line,
                         end_line, content_hash, text, embedding, embedding_dim, indexed_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    chunks.stream().map(c -> new Object[] {
                            c.repository(), c.gitRef(), c.commitSha(), c.filePath(), c.chunkType(), c.symbol(),
                            c.startLine(), c.endLine(), c.contentHash(), c.text(),
                            EmbeddingCodec.encode(c.embedding()),
                            c.embedding() == null ? null : c.embedding().length,
                            indexedAt
                    }).toList());
            jdbc.execute("COMMIT");
        } catch (RuntimeException e) {
            rollbackQuietly();
            throw e;
        }
    }

    private void rollbackQuietly() {
        try {
            jdbc.execute("ROLLBACK");
        } catch (RuntimeException ignored) {
            // The transaction may already have been rolled back by the driver.
        }
    }

    public List<CodeChunk> findByCommit(String repository, String commitSha) {
        return jdbc.query("""
                SELECT id, repository, git_ref, commit_sha, file_path, chunk_type, symbol, start_line,
                       end_line, content_hash, text, embedding
                FROM code_chunk WHERE repository = ? AND commit_sha = ?
                ORDER BY file_path, start_line
                """, (rs, rowNum) -> new CodeChunk(
                        rs.getLong("id"),
                        rs.getString("repository"),
                        rs.getString("git_ref"),
                        rs.getString("commit_sha"),
                        rs.getString("file_path"),
                        rs.getString("chunk_type"),
                        rs.getString("symbol"),
                        rs.getInt("start_line"),
                        rs.getInt("end_line"),
                        rs.getString("content_hash"),
                        rs.getString("text"),
                        EmbeddingCodec.decode(rs.getBytes("embedding"))), repository, commitSha);
    }

    public List<CodeChunk> findByFile(String repository, String commitSha, String filePath) {
        return jdbc.query("""
                SELECT id, repository, git_ref, commit_sha, file_path, chunk_type, symbol, start_line,
                       end_line, content_hash, text, embedding
                FROM code_chunk WHERE repository = ? AND commit_sha = ? AND file_path = ?
                ORDER BY start_line
                """, (rs, rowNum) -> new CodeChunk(
                        rs.getLong("id"),
                        rs.getString("repository"),
                        rs.getString("git_ref"),
                        rs.getString("commit_sha"),
                        rs.getString("file_path"),
                        rs.getString("chunk_type"),
                        rs.getString("symbol"),
                        rs.getInt("start_line"),
                        rs.getInt("end_line"),
                        rs.getString("content_hash"),
                        rs.getString("text"),
                        EmbeddingCodec.decode(rs.getBytes("embedding"))), repository, commitSha, filePath);
    }

    public int countByCommit(String repository, String commitSha) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_chunk WHERE repository = ? AND commit_sha = ?",
                Integer.class, repository, commitSha);
        return count == null ? 0 : count;
    }

    public boolean hasCommit(String repository, String commitSha) {
        return countByCommit(repository, commitSha) > 0;
    }

    public void deleteByCommit(String repository, String commitSha) {
        jdbc.update("DELETE FROM code_chunk WHERE repository = ? AND commit_sha = ?", repository, commitSha);
    }

    public String lastIndexedAt(String repository, String commitSha) {
        List<String> values = jdbc.queryForList(
                "SELECT MAX(indexed_at) FROM code_chunk WHERE repository = ? AND commit_sha = ?",
                String.class, repository, commitSha);
        return values.isEmpty() ? null : values.getFirst();
    }
}
