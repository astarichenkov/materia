package com.lectureprocessor.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.domain.LectureChunk;
import com.lectureprocessor.domain.RagAnswer;
import com.lectureprocessor.llm.LlmOptions;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.FixedTaskResolver;
import com.lectureprocessor.text.PromptTemplates;

class RagServiceTest {

    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final FakeLlmProvider llm = new FakeLlmProvider();
    private final RagService service = new RagService(retrieval, new FixedTaskResolver(llm),
            new PromptTemplates());

    @Test
    void answersWhenEvidenceFoundAndBuildsRealSources() {
        LectureChunk chunk = new LectureChunk(7, "L1", 3, 10_000, 20_000,
                "Вектор — это направленный отрезок.", FakeLlmProvider.embedOne("вектор"));
        when(retrieval.retrieve("L1", "что такое вектор")).thenReturn(List.of(new ScoredChunk(chunk, 0.9)));
        llm.setChatFunction((system, user) -> "Вектор — это направленный отрезок.");

        RagAnswer answer = service.answer("L1", "lecture.mp4", "что такое вектор", List.of(),
                LlmOptions.of("fake-model"));

        assertThat(answer.grounded()).isTrue();
        assertThat(answer.sources()).hasSize(1);
        assertThat(answer.sources().getFirst().chunkId()).isEqualTo(7);
        assertThat(answer.sources().getFirst().startMs()).isEqualTo(10_000);
        assertThat(answer.sources().getFirst().score()).isEqualTo(0.9);
        assertThat(answer.sources().getFirst().excerpt()).contains("Вектор");
    }

    @Test
    void clearsSourcesAndGroundedFlagWhenModelRefuses() {
        LectureChunk chunk = new LectureChunk(7, "L1", 3, 10_000, 20_000,
                "Вектор — это направленный отрезок.", FakeLlmProvider.embedOne("погода"));
        when(retrieval.retrieve("L1", "какая погода"))
                .thenReturn(List.of(new ScoredChunk(chunk, 0.71)));
        llm.setChatFunction((system, user) ->
                "Информации о погоде в предоставленных источниках нет.");

        RagAnswer answer = service.answer("L1", "lecture.mp4", "какая погода", List.of(),
                LlmOptions.of("fake-model"));

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.sources()).isEmpty();
        assertThat(answer.text()).contains("нет");
    }

    @Test
    void returnsNoEvidenceWhenNothingRetrieved() {
        when(retrieval.retrieve("L1", "нерелевантный вопрос")).thenReturn(List.of());

        RagAnswer answer = service.answer("L1", "lecture.mp4", "нерелевантный вопрос", List.of(),
                LlmOptions.of("fake-model"));

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.sources()).isEmpty();
        assertThat(answer.text()).contains("не найдено");
    }
}
