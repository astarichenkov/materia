package com.lectureprocessor.fileagent.repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lectureprocessor.fileagent.FileWorkspace;
import com.lectureprocessor.fileagent.ProposalStatus;
import com.lectureprocessor.fileagent.model.FileChangeProposal;

/** Persistence for immutable, versioned change proposals. */
@Repository
public class FileChangeProposalRepository {

    private final JdbcTemplate jdbc;

    public FileChangeProposalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<FileChangeProposal> mapper = (ResultSet rs, int rowNum) ->
            new FileChangeProposal(rs.getLong("id"), rs.getString("task_id"), rs.getInt("version"),
                    ProposalStatus.valueOf(rs.getString("status")),
                    FileWorkspace.valueOf(rs.getString("workspace")),
                    rs.getString("lecture_id"), rs.getString("summary"), rs.getString("diff"),
                    rs.getString("provider"), rs.getString("model"), rs.getString("error"),
                    rs.getString("backup_dir"), rs.getString("approved_by"),
                    rs.getString("verification"), Instant.parse(rs.getString("created_at")),
                    instant(rs.getString("approved_at")), instant(rs.getString("applied_at")));

    public FileChangeProposal insert(FileChangeProposal proposal) {
        jdbc.update("""
                INSERT INTO file_change_proposal (task_id, version, status, workspace, lecture_id,
                        summary, diff, provider, model, error, backup_dir, approved_by, verification,
                        created_at, approved_at, applied_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, proposal.taskId(), proposal.version(), proposal.status().name(),
                proposal.workspace().name(), proposal.lectureId(), proposal.summary(),
                proposal.diff(), proposal.provider(), proposal.model(), proposal.error(),
                proposal.backupDir(), proposal.approvedBy(), proposal.verification(),
                proposal.createdAt().toString(), instantText(proposal.approvedAt()),
                instantText(proposal.appliedAt()));
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return new FileChangeProposal(id, proposal.taskId(), proposal.version(), proposal.status(),
                proposal.workspace(), proposal.lectureId(), proposal.summary(), proposal.diff(),
                proposal.provider(), proposal.model(), proposal.error(), proposal.backupDir(),
                proposal.approvedBy(), proposal.verification(), proposal.createdAt(),
                proposal.approvedAt(), proposal.appliedAt());
    }

    public void updateStatus(long id, ProposalStatus status, String error, String backupDir,
            String approvedBy, String verification, Instant approvedAt, Instant appliedAt) {
        jdbc.update("""
                UPDATE file_change_proposal
                SET status = ?, error = ?, backup_dir = COALESCE(?, backup_dir),
                    approved_by = COALESCE(?, approved_by), verification = COALESCE(?, verification),
                    approved_at = COALESCE(?, approved_at), applied_at = COALESCE(?, applied_at)
                WHERE id = ?
                """, status.name(), error, backupDir, approvedBy, verification,
                instantText(approvedAt), instantText(appliedAt), id);
    }

    /** Atomically moves a proposal from an expected state to a new state. */
    public boolean transition(long id, ProposalStatus expected, ProposalStatus next, String error) {
        return jdbc.update("""
                UPDATE file_change_proposal SET status = ?, error = ?
                WHERE id = ? AND status = ?
                """, next.name(), error, id, expected.name()) == 1;
    }

    public Optional<FileChangeProposal> findById(long id) {
        return jdbc.query("SELECT * FROM file_change_proposal WHERE id = ?", mapper, id)
                .stream().findFirst();
    }

    public List<FileChangeProposal> findByTask(String taskId) {
        return jdbc.query("SELECT * FROM file_change_proposal WHERE task_id = ? ORDER BY version DESC",
                mapper, taskId);
    }

    public Optional<FileChangeProposal> latestByTask(String taskId) {
        return jdbc.query("""
                SELECT * FROM file_change_proposal WHERE task_id = ?
                ORDER BY version DESC LIMIT 1
                """, mapper, taskId).stream().findFirst();
    }

    public int countByStatus(ProposalStatus status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM file_change_proposal WHERE status = ?", Integer.class,
                status.name());
        return count == null ? 0 : count;
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM file_change_proposal", Integer.class);
        return count == null ? 0 : count;
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static String instantText(Instant value) {
        return value == null ? null : value.toString();
    }
}
