package com.lectureprocessor.fileagent.workspace;

/** One code symbol occurrence with its source range. */
public record SymbolHit(String path, String symbol, String kind, int startLine, int endLine,
        String snippet) {
}
