package com.lectureprocessor.fileagent.model;

import java.util.Map;

import com.lectureprocessor.fileagent.workspace.FileContent;

/** Aggregated, bounded model context plus the exact files that were read. */
public record FileAgentContext(
        String promptText,
        Map<String, FileContent> readFiles,
        int contextChars) {
}
