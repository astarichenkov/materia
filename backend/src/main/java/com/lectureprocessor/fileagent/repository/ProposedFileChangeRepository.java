package com.lectureprocessor.fileagent.repository;

import java.sql.ResultSet;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.fileagent.FileChangeType;
import com.lectureprocessor.fileagent.model.ProposedFileChange;

/** Persistence for the per-file entries of a proposal. */
@Repository
public class ProposedFileChangeRepository {

    private final JdbcTemplate jdbc;

    public ProposedFileChangeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<ProposedFileChange> mapper = (ResultSet rs, int rowNum) ->
            new ProposedFileChange(rs.getLong("id"), rs.getLong("proposal_id"),
                    rs.getString("path"), FileChangeType.valueOf(rs.getString("change_type")),
                    rs.getString("original_hash"), rs.getString("new_hash"),
                    rs.getString("original_content"), rs.getString("new_content"),
                    rs.getString("reason"));

    public void insert(long proposalId, ProposedFileChange change) {
        jdbc.update("""
                INSERT INTO proposed_file_change (proposal_id, path, change_type, original_hash,
                        new_hash, original_content, new_content, reason)
                VALUES (?,?,?,?,?,?,?,?)
                """, proposalId, change.path(), change.type().name(), change.originalHash(),
                change.newHash(), change.originalContent(), change.newContent(), change.reason());
    }

    public List<ProposedFileChange> findByProposal(long proposalId) {
        return jdbc.query("SELECT * FROM proposed_file_change WHERE proposal_id = ? ORDER BY id",
                mapper, proposalId);
    }
}
