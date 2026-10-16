package com.lectureprocessor.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.EmbeddingProvider;

/**
 * Indexes project documentation once at startup when enabled and the local
 * embedding model is reachable. Failures are logged and never block startup;
 * the index can always be rebuilt via {@code POST /api/assistant/index}.
 */
@Component
public class DocumentationIndexRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentationIndexRunner.class);

    private final AppProperties properties;
    private final DocumentationIndexService indexService;
    private final EmbeddingProvider llm;

    public DocumentationIndexRunner(AppProperties properties, DocumentationIndexService indexService,
            EmbeddingProvider llm) {
        this.properties = properties;
        this.indexService = indexService;
        this.llm = llm;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void indexOnStartup() {
        if (!properties.assistant().autoIndex()) {
            return;
        }
        if (indexService.isIndexed()) {
            log.info("Documentation RAG already indexed ({} chunks)", indexService.status().chunkCount());
            return;
        }
        if (!llm.isAvailable()) {
            log.warn("Documentation RAG not indexed: local embedding model is unavailable");
            return;
        }
        try {
            IndexResult result = indexService.index();
            log.info("Documentation RAG auto-indexed: {} documents, {} chunks",
                    result.documents(), result.chunks());
        } catch (RuntimeException e) {
            log.warn("Documentation RAG auto-index failed: {}", e.getMessage());
        }
    }
}
