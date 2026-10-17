package com.lectureprocessor.fileagent.model;

import java.util.List;

/** Plan selected by the LLM (or deterministic fallback) before reading files. */
public record FileAgentPlan(
        String reasoning,
        List<String> queries,
        List<String> symbols,
        List<String> readFiles,
        String proposalKind,
        List<String> targetPaths,
        List<String> notes) {

    public FileAgentPlan withReadFiles(List<String> files) {
        return new FileAgentPlan(reasoning, queries, symbols, files, proposalKind, targetPaths, notes);
    }

    public String toText() {
        StringBuilder text = new StringBuilder();
        text.append("reasoning: ").append(reasoning == null ? "" : reasoning).append('\n');
        text.append("queries: ").append(String.join(", ", safe(queries))).append('\n');
        text.append("symbols: ").append(String.join(", ", safe(symbols))).append('\n');
        text.append("readFiles: ").append(String.join(", ", safe(readFiles))).append('\n');
        text.append("proposalKind: ").append(proposalKind == null ? "GENERAL" : proposalKind).append('\n');
        text.append("targetPaths: ").append(String.join(", ", safe(targetPaths))).append('\n');
        if (notes != null && !notes.isEmpty()) {
            text.append("notes: ").append(String.join("; ", notes)).append('\n');
        }
        return text.toString();
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
