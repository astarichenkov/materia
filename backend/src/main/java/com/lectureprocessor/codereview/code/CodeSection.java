package com.lectureprocessor.codereview.code;

/**
 * A structurally derived code section ready to become a {@code code_chunk}.
 */
public record CodeSection(String symbol, String chunkType, int startLine, int endLine, String text) {
}
