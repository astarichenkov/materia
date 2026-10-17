package com.lectureprocessor.fileagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.fileagent.model.ChangeProposalDraft;

import tools.jackson.databind.ObjectMapper;

class FileAgentProposalParserTest {

    private final FileAgentProposalParser parser = new FileAgentProposalParser(new ObjectMapper());

    @Test
    void parsesFencedJson() {
        String raw = """
                ```json
                {"summary":"s","changes":[{"path":"README.md","action":"MODIFY",
                "content":"# New","reason":"r"}],"notes":[]}
                ```
                """;
        ChangeProposalDraft draft = parser.parse(raw);
        assertThat(draft.summary()).isEqualTo("s");
        assertThat(draft.changes()).hasSize(1);
        assertThat(draft.changes().getFirst().content()).isEqualTo("# New");
    }

    @Test
    void repairsRawNewlinesInsideStringLiterals() {
        String raw = "{\"summary\":\"line1\nline2\",\"changes\":[{\"path\":\"README.md\","
                + "\"action\":\"MODIFY\",\"content\":\"# Title\n\nbody\",\"reason\":\"r\"}]}";
        ChangeProposalDraft draft = parser.parse(raw);
        assertThat(draft.summary()).contains("line1\nline2");
        assertThat(draft.changes().getFirst().content()).contains("# Title\n\nbody");
    }

    @Test
    void rejectsNonJsonAndEmptyChanges() {
        assertThatThrownBy(() -> parser.parse("no json here"))
                .isInstanceOf(FileAgentException.class);
        assertThatThrownBy(() -> parser.parse("{\"summary\":\"s\",\"changes\":[]}"))
                .isInstanceOf(FileAgentException.class);
    }

    @Test
    void adrNormalizerKeepsFirstSectionOnly() {
        String adr = "# ADR\n\n## Status\nAccepted\n\n## Context\nA\n\n## Decision\nB\n\n"
                + "## Context\nA again\n\n## Decision\nB again\n";
        String normalized = FileAgentPatchValidator.normalizeAdr(adr);
        assertThat(normalized).contains("## Context\nA\n");
        assertThat(normalized).doesNotContain("A again");
        assertThat(normalized).doesNotContain("B again");
    }
}
