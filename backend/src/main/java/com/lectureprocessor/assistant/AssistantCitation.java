package com.lectureprocessor.assistant;

/**
 * A documentation source referenced by an assistant answer. Built from retrieval
 * metadata, never from model output.
 */
public record AssistantCitation(
        String sourcePath,
        String heading,
        int sequence,
        double score,
        String excerpt) {
}
