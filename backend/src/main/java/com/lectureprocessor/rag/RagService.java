package com.lectureprocessor.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.LectureChunk;
import com.lectureprocessor.domain.RagAnswer;
import com.lectureprocessor.domain.SourceRef;
import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.llm.LlmOptions;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.text.PromptTemplates;

/**
 * Retrieval-augmented generation grounded strictly in retrieved transcript
 * chunks. Source references are built from retrieval metadata, never from the
 * model output.
 */
@Service
public class RagService {

    private static final int EXCERPT_LENGTH = 240;

    private final RetrievalService retrieval;
    private final LlmTaskResolver taskResolver;
    private final PromptTemplates prompts;

    public RagService(RetrievalService retrieval, LlmTaskResolver taskResolver, PromptTemplates prompts) {
        this.retrieval = retrieval;
        this.taskResolver = taskResolver;
        this.prompts = prompts;
    }

    public RagAnswer answer(String lectureId, String lectureFilename, String question,
            List<String> recentHistory, LlmOptions options) {
        List<ScoredChunk> retrieved = retrieval.retrieve(lectureId, question);
        if (retrieved.isEmpty()) {
            return RagAnswer.noEvidence();
        }

        StringBuilder context = new StringBuilder();
        List<SourceRef> sources = new ArrayList<>();
        int ref = 0;
        for (ScoredChunk scored : retrieved) {
            LectureChunk chunk = scored.chunk();
            ref++;
            context.append("[").append(ref).append("] (")
                    .append(Transcript.formatTimestamp(chunk.startMs())).append("–")
                    .append(Transcript.formatTimestamp(chunk.endMs())).append(") ")
                    .append(chunk.text()).append("\n\n");
            sources.add(new SourceRef(lectureId, lectureFilename, chunk.id(), chunk.sequence(),
                    chunk.startMs(), chunk.endMs(), scored.score(), excerpt(chunk.text())));
        }

        StringBuilder user = new StringBuilder();
        if (recentHistory != null && !recentHistory.isEmpty()) {
            user.append("Недавний диалог:\n");
            recentHistory.forEach(line -> user.append(line).append('\n'));
            user.append('\n');
        }
        user.append("Источники из лекции:\n\n").append(context)
                .append("\nВопрос: ").append(question);

        ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.LECTURE_PROCESSING);
        String answer = resolved.provider().chat(prompts.get(PromptTemplates.RAG_ANSWER), user.toString(),
                options).strip();
        if (answer.isBlank()) {
            return RagAnswer.noEvidence();
        }
        // The model may honestly refuse when the retrieved context does not
        // contain the answer. In that case the answer is NOT grounded: drop the
        // retrieval metadata so no fabricated sources or timestamps are shipped.
        if (NoEvidenceDetector.isRefusal(answer)) {
            return new RagAnswer(answer, List.of(), false);
        }
        return new RagAnswer(answer, sources, true);
    }

    static String excerpt(String text) {
        String stripped = text.strip();
        return stripped.length() <= EXCERPT_LENGTH ? stripped : stripped.substring(0, EXCERPT_LENGTH) + "…";
    }
}
