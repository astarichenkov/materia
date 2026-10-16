package com.lectureprocessor.support;

/**
 * One piece of evidence used to answer. {@code kind} is {@code DOC} (documentation
 * RAG) or {@code TOOL} (confirmed persisted facts / ticket context).
 */
public record SupportEvidence(String kind, String label, String content, SupportCitation citation) {
}
