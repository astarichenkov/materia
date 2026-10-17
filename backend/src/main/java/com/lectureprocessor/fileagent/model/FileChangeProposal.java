package com.lectureprocessor.fileagent.model;

import java.time.Instant;

import com.lectureprocessor.fileagent.FileWorkspace;
import com.lectureprocessor.fileagent.ProposalStatus;

/** Immutable, versioned set of file changes awaiting human approval. */
public record FileChangeProposal(
        Long id,
        String taskId,
        int version,
        ProposalStatus status,
        FileWorkspace workspace,
        String lectureId,
        String summary,
        String diff,
        String provider,
        String model,
        String error,
        String backupDir,
        String approvedBy,
        String verification,
        Instant createdAt,
        Instant approvedAt,
        Instant appliedAt) {
}
