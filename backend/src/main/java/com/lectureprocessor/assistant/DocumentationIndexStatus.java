package com.lectureprocessor.assistant;

import java.util.List;

/** Snapshot of the documentation RAG index for the UI/API. */
public record DocumentationIndexStatus(
        String projectRoot,
        int sourceCount,
        int chunkCount,
        String lastIndexedAt,
        List<String> sources) {
}
