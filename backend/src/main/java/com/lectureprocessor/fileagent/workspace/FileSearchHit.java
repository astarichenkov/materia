package com.lectureprocessor.fileagent.workspace;

/** One lexical file search hit, scoped to a workspace. */
public record FileSearchHit(String path, double score, String match, long sizeBytes) {
}
