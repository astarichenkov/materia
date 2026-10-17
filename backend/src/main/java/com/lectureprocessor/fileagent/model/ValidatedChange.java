package com.lectureprocessor.fileagent.model;

import com.lectureprocessor.fileagent.FileChangeType;

/** A change that passed path, size, hash and content validation. */
public record ValidatedChange(
        String path,
        FileChangeType type,
        String originalHash,
        String newHash,
        String originalContent,
        String newContent,
        String reason) {
}
