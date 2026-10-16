package com.lectureprocessor.assistant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.EmbeddingProvider;
import com.lectureprocessor.repository.DocChunkRepository;

/**
 * Builds the project-documentation vector index. Embeddings come from the same
 * local embedding model as lecture RAG, but live in a dedicated table so the two
 * collections can never be mixed.
 */
@Service
public class DocumentationIndexService {

    private static final Logger log = LoggerFactory.getLogger(DocumentationIndexService.class);
    private static final int EMBED_BATCH = 16;

    private final DocumentationCorpusService corpus;
    private final MarkdownChunker chunker;
    private final EmbeddingProvider llm;
    private final DocChunkRepository repository;
    private final AppProperties properties;
    private final ProjectRootResolver roots;

    public DocumentationIndexService(DocumentationCorpusService corpus, MarkdownChunker chunker,
            EmbeddingProvider llm, DocChunkRepository repository, AppProperties properties,
            ProjectRootResolver roots) {
        this.corpus = corpus;
        this.chunker = chunker;
        this.llm = llm;
        this.repository = repository;
        this.properties = properties;
        this.roots = roots;
    }

    /** Reindexes the whole documentation corpus (idempotent). */
    public IndexResult index() {
        AppProperties.Assistant assistant = properties.assistant();
        List<DocSource> sources = corpus.load();
        List<DocChunk> chunks = new ArrayList<>();
        int maxPerDoc = Math.max(1, assistant.maxChunksPerDoc());

        for (DocSource source : sources) {
            List<DocSection> sections = chunker.chunk(source.content(),
                    assistant.chunkChars(), assistant.chunkOverlapChars());
            if (sections.isEmpty()) {
                continue;
            }
            if (sections.size() > maxPerDoc) {
                log.warn("Documentation {} has {} chunks, capped at {}", source.path(),
                        sections.size(), maxPerDoc);
                sections = sections.subList(0, maxPerDoc);
            }
            List<float[]> vectors = embed(sections.stream().map(DocSection::text).toList());
            for (int i = 0; i < sections.size(); i++) {
                DocSection section = sections.get(i);
                chunks.add(new DocChunk(0, source.path(), source.title(), section.heading(), i,
                        section.text(), vectors.get(i)));
            }
        }
        repository.replaceAll(chunks, Instant.now().toString());
        log.info("Documentation RAG indexed: {} documents, {} chunks", sources.size(), chunks.size());
        return new IndexResult(sources.size(), chunks.size());
    }

    public boolean isIndexed() {
        return repository.count() > 0;
    }

    public DocumentationIndexStatus status() {
        List<String> sources = repository.findAll().stream()
                .map(DocChunk::sourcePath)
                .distinct()
                .sorted()
                .toList();
        return new DocumentationIndexStatus(
                roots.resolve().toString(),
                repository.countSources(),
                repository.count(),
                repository.lastIndexedAt(),
                sources);
    }

    private List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += EMBED_BATCH) {
            List<String> batch = texts.subList(start, Math.min(start + EMBED_BATCH, texts.size()));
            vectors.addAll(llm.embed(batch));
        }
        return vectors;
    }
}
