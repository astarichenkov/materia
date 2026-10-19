package com.lectureprocessor.domain;

import java.util.List;

/**
 * Answer produced by the RAG pipeline together with its grounded sources.
 */
public record RagAnswer(String text, List<SourceRef> sources, boolean grounded) {

    public RagAnswer {
        sources = List.copyOf(sources);
    }

    public static RagAnswer noEvidence() {
        return new RagAnswer(
                "В материале не найдено информации, достаточной для ответа на этот вопрос.",
                List.of(), false);
    }
}
