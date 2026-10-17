package com.lectureprocessor.fileagent.model;

import com.lectureprocessor.fileagent.FileChangeType;

/** One CREATE/MODIFY entry inside a proposal. */
public record ProposedFileChange(
        Long id,
        long proposalId,
        String path,
        FileChangeType type,
        String originalHash,
        String newHash,
        String originalContent,
        String newContent,
        String reason) {
}
