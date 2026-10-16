package com.lectureprocessor.assistant;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Splits Markdown/Markdown-like text into heading-scoped chunks bounded by
 * {@code maxChars}. Headings are carried into each chunk so retrieval keeps
 * semantic context. Overlap is applied by prefixing the tail of the previous
 * chunk, which preserves cross-chunk continuity for long sections.
 */
@Component
public class MarkdownChunker {

    public List<DocSection> chunk(String content, int maxChars, int overlapChars) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        int limit = Math.max(200, maxChars);
        int overlap = Math.max(0, Math.min(overlapChars, limit / 2));

        List<DocSection> sections = new ArrayList<>();
        String heading = "";
        StringBuilder chunk = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();

        for (String rawLine : content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.stripTrailing();
            if (line.matches("^#{1,6}\\s+.*")) {
                flushParagraph(sections, chunk, paragraph, heading, limit, overlap);
                flushChunk(sections, chunk, heading, 0);
                heading = line.replaceFirst("^#{1,6}\\s+", "").strip();
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(sections, chunk, paragraph, heading, limit, overlap);
                continue;
            }
            paragraph.append(line).append('\n');
        }
        flushParagraph(sections, chunk, paragraph, heading, limit, overlap);
        flushChunk(sections, chunk, heading, overlap);
        return sections;
    }

    private void flushParagraph(List<DocSection> sections, StringBuilder chunk, StringBuilder paragraph,
            String heading, int limit, int overlap) {
        if (paragraph.isEmpty()) {
            return;
        }
        String text = paragraph.toString().strip();
        paragraph.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        if (chunk.length() > 0 && chunk.length() + text.length() + 2 > limit) {
            flushChunk(sections, chunk, heading, overlap);
        }
        // A single paragraph larger than the limit is split on sentence boundaries.
        while (text.length() > limit) {
            int cut = text.lastIndexOf(". ", Math.min(limit, text.length() - 1));
            if (cut < limit / 2) {
                cut = Math.min(limit, text.length());
            } else {
                cut += 1;
            }
            String head = text.substring(0, cut).strip();
            text = text.substring(cut).strip();
            if (chunk.length() > 0) {
                flushChunk(sections, chunk, heading, overlap);
            }
            chunk.append(head).append("\n\n");
            flushChunk(sections, chunk, heading, overlap);
        }
        if (!text.isEmpty()) {
            if (chunk.length() > 0) {
                chunk.append('\n').append('\n');
            }
            chunk.append(text);
        }
    }

    private void flushChunk(List<DocSection> sections, StringBuilder chunk, String heading, int overlap) {
        String body = chunk.toString().strip();
        if (body.isEmpty()) {
            return;
        }
        String text = heading.isBlank() ? body : "## " + heading + "\n" + body;
        sections.add(new DocSection(heading, text));
        chunk.setLength(0);
        if (overlap > 0 && text.length() > overlap) {
            String tail = text.substring(text.length() - overlap).strip();
            if (!tail.isEmpty()) {
                chunk.append(tail);
            }
        }
    }
}
