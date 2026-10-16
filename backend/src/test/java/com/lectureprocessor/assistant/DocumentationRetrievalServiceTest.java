package com.lectureprocessor.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.repository.DocChunkRepository;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.TestProperties;

class DocumentationRetrievalServiceTest {

    private final DocChunkRepository repository = mock(DocChunkRepository.class);
    private final FakeLlmProvider llm = new FakeLlmProvider();
    private final DocumentationRetrievalService service = new DocumentationRetrievalService(repository,
            llm, TestProperties.defaults("./target/test-data"));

    @Test
    void ordersChunksByCosineSimilarity() {
        float[] relevant = FakeLlmProvider.embedOne("пайплайн whisper транскрибация");
        DocChunk matching = new DocChunk(1, "docs/pipeline.md", "Pipeline", "Этапы", 0,
                "Пайплайн содержит Whisper и транскрибацию", relevant.clone());
        DocChunk unrelated = new DocChunk(2, "docs/other.md", "Other", "Прочее", 0,
                "совершенно другой текст про музыку и кино", new float[relevant.length]);
        when(repository.findAll()).thenReturn(List.of(unrelated, matching));

        List<DocSearchResult> results = service.search("пайплайн whisper", 5, 0.0);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().chunk().sourcePath()).isEqualTo("docs/pipeline.md");
    }

    @Test
    void returnsEmptyWhenIndexIsEmpty() {
        when(repository.findAll()).thenReturn(List.of());
        assertThat(service.search("вопрос", 5, 0.0)).isEmpty();
    }

    @Test
    void blankQuestionReturnsEmpty() {
        assertThat(service.search("  ", 5, 0.0)).isEmpty();
    }
}
