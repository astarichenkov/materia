package com.lectureprocessor.pipeline.stage;

import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.pipeline.ArtifactManifest;
import com.lectureprocessor.pipeline.Fingerprints;
import com.lectureprocessor.pipeline.JobContext;
import com.lectureprocessor.pipeline.PipelineDataLoader;
import com.lectureprocessor.pipeline.PipelineStage;
import com.lectureprocessor.pipeline.StageExecutor;
import com.lectureprocessor.pipeline.StageResult;
import com.lectureprocessor.rag.EmbeddingIndexService;
import com.lectureprocessor.storage.FileStorageService;

/**
 * Optional RAG embedding index. A failure here never fails the job and never
 * prevents document export — the index can be rebuilt later.
 */
@Component
public class RagIndexingStage implements StageExecutor {

    private final EmbeddingIndexService embeddingIndex;
    private final FileStorageService storage;
    private final AppProperties properties;
    private final PipelineDataLoader loader;

    public RagIndexingStage(EmbeddingIndexService embeddingIndex, FileStorageService storage,
            AppProperties properties, PipelineDataLoader loader) {
        this.embeddingIndex = embeddingIndex;
        this.storage = storage;
        this.properties = properties;
        this.loader = loader;
    }

    @Override
    public PipelineStage id() {
        return PipelineStage.RAG_INDEXING;
    }

    @Override
    public boolean optional() {
        return true;
    }

    @Override
    public boolean requiredForExport() {
        return false;
    }

    @Override
    public String inputFingerprint(JobContext context) {
        String transcriptKey = Fingerprints.mediaKey(
                storage.resolve(context.lectureId(), PipelineDataLoader.TRANSCRIPT_JSON));
        return Fingerprints.of("rag-indexing", transcriptKey,
                String.valueOf(properties.ollama().embeddingModel()),
                String.valueOf(properties.processing().ragChunkChars()),
                String.valueOf(properties.processing().ragChunkOverlapChars()));
    }

    @Override
    public StageResult execute(JobContext context) {
        long start = System.currentTimeMillis();
        Transcript transcript = context.transcript();
        if (transcript == null) {
            transcript = loader.transcript(context.lectureId());
        }
        if (transcript == null || transcript.isEmpty()) {
            return StageResult.completed(id(), ArtifactManifest.empty(),
                    System.currentTimeMillis() - start);
        }
        embeddingIndex.index(context.lectureId(), transcript.segments());
        return StageResult.completed(id(), ArtifactManifest.empty(), System.currentTimeMillis() - start);
    }
}
