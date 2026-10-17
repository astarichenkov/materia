package com.lectureprocessor.fileagent.model;

/** Parsed, not yet validated change emitted by the model. */
public record ProposedChangeDraft(String path, String action, String content, String reason) {
}
