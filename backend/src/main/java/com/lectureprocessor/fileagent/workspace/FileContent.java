package com.lectureprocessor.fileagent.workspace;

import java.time.Instant;
import java.util.List;

/** Bounded, checksum-versioned content of one workspace file. */
public record FileContent(
        String workspace,
        String path,
        List<String> lines,
        int firstLine,
        int totalLines,
        boolean truncated,
        String sha256,
        long sizeBytes,
        Instant lastModified) {

    public String numberedText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            text.append(firstLine + i).append('\t').append(lines.get(i)).append('\n');
        }
        return text.toString();
    }

    public String text() {
        return String.join("\n", lines);
    }
}
