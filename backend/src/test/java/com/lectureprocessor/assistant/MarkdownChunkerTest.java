package com.lectureprocessor.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker();

    @Test
    void splitsByHeadingsAndKeepsHeadingInText() {
        String markdown = """
                # Project

                Introduction paragraph.

                ## Pipeline

                First stage description.

                ## Storage

                SQLite storage description.
                """;

        List<DocSection> sections = chunker.chunk(markdown, 1200, 0);

        assertThat(sections).hasSize(3);
        assertThat(sections.get(0).heading()).isEqualTo("Project");
        assertThat(sections.get(0).text()).contains("## Project").contains("Introduction paragraph.");
        assertThat(sections.get(1).heading()).isEqualTo("Pipeline");
        assertThat(sections.get(2).heading()).isEqualTo("Storage");
    }

    @Test
    void respectsMaximumChunkSize() {
        StringBuilder markdown = new StringBuilder("# Big\n\n");
        for (int i = 0; i < 60; i++) {
            markdown.append("Paragraph number ").append(i)
                    .append(" with some meaningful content to force splitting.\n\n");
        }

        List<DocSection> sections = chunker.chunk(markdown.toString(), 300, 0);

        assertThat(sections.size()).isGreaterThan(1);
        assertThat(sections).allSatisfy(section -> assertThat(section.text().length()).isLessThan(900));
    }

    @Test
    void emptyInputProducesNoChunks() {
        assertThat(chunker.chunk("", 500, 0)).isEmpty();
        assertThat(chunker.chunk("   \n  ", 500, 0)).isEmpty();
    }
}
