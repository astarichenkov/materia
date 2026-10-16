package com.lectureprocessor.codereview.code;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.gitea.GiteaApiException;
import com.lectureprocessor.gitea.GiteaClient;
import com.lectureprocessor.gitea.GiteaTreeEntry;
import com.lectureprocessor.llm.EmbeddingProvider;
import com.lectureprocessor.repository.CodeChunkRepository;

/**
 * Builds the source-code RAG index for a specific commit, fetched from Gitea.
 *
 * <p>Commit consistency is the core property: code is never taken from the local
 * working directory for an arbitrary PR. The index is keyed by
 * {@code (repository, commit_sha)} and reused across reviews of the same head.</p>
 */
@Service
public class CodeIndexService {

    private static final Logger log = LoggerFactory.getLogger(CodeIndexService.class);
    private static final int EMBED_BATCH = 16;

    private final GiteaClient gitea;
    private final EmbeddingProvider embeddings;
    private final CodeChunkRepository repository;
    private final AppProperties properties;

    public CodeIndexService(GiteaClient gitea, EmbeddingProvider embeddings, CodeChunkRepository repository,
            AppProperties properties) {
        this.gitea = gitea;
        this.embeddings = embeddings;
        this.repository = repository;
        this.properties = properties;
    }

    public boolean isIndexed(String repositoryId, String commitSha) {
        return repositoryId != null && commitSha != null && repository.hasCommit(repositoryId, commitSha);
    }

    public int chunkCount(String repositoryId, String commitSha) {
        return repository.countByCommit(repositoryId, commitSha);
    }

    public CodeIndexResult ensureIndexed(String repositoryId, String commitSha, boolean force) {
        AppProperties.CodeReview review = properties.codeReview();
        if (!review.autoIndex()) {
            return CodeIndexResult.reused(commitSha, 0, repository.countByCommit(repositoryId, commitSha));
        }
        if (!force && repository.hasCommit(repositoryId, commitSha)) {
            int count = repository.countByCommit(repositoryId, commitSha);
            return CodeIndexResult.reused(commitSha, 0, count);
        }
        if (!embeddings.isAvailable()) {
            return CodeIndexResult.unavailable(commitSha,
                    "embedding provider недоступен — code RAG пропущен");
        }
        List<GiteaTreeEntry> tree;
        try {
            tree = gitea.listTree(repositoryId, commitSha);
        } catch (GiteaApiException e) {
            return CodeIndexResult.unavailable(commitSha, "не удалось получить дерево файлов: " + e.getMessage());
        }
        List<GiteaTreeEntry> files = tree.stream()
                .filter(entry -> indexable(entry.path(), review))
                .limit(Math.max(1, review.maxCodeIndexFiles()))
                .toList();

        List<CodeChunk> chunks = new ArrayList<>();
        int indexedFiles = 0;
        for (GiteaTreeEntry entry : files) {
            if (chunks.size() >= review.maxCodeIndexChunks()) {
                break;
            }
            String content;
            try {
                content = gitea.getBlob(repositoryId, entry.sha());
            } catch (GiteaApiException e) {
                log.debug("Skipping {}: {}", entry.path(), e.getMessage());
                continue;
            }
            if (content == null || content.isBlank()) {
                continue;
            }
            indexedFiles++;
            List<CodeSection> sections = entry.path().toLowerCase(Locale.ROOT).endsWith(".java")
                    ? JavaCodeChunker.chunk(content, Math.max(400, review.chunkChars()))
                    : fixedChunks(content, Math.max(400, review.chunkChars()));
            for (CodeSection section : sections) {
                if (chunks.size() >= review.maxCodeIndexChunks()) {
                    break;
                }
                chunks.add(new CodeChunk(null, repositoryId, commitSha, commitSha, entry.path(),
                        section.chunkType(), section.symbol(), section.startLine(), section.endLine(),
                        sha256(section.text()), section.text(), null));
            }
        }
        embedInBatches(chunks);
        repository.replaceForCommit(repositoryId, commitSha, commitSha, chunks, Instant.now().toString());
        log.info("Indexed code for {}@{}: {} files, {} chunks", repositoryId,
                commitSha == null ? "?" : commitSha.substring(0, Math.min(8, commitSha.length())),
                indexedFiles, chunks.size());
        return new CodeIndexResult(true, false, false, indexedFiles, chunks.size(), commitSha, null);
    }

    private boolean indexable(String path, AppProperties.CodeReview review) {
        if (path == null || path.isBlank()) {
            return false;
        }
        if (PathGlob.startsWithAny(path, review.codePaths())
                || isBuildOrApiFile(path)) {
            return !PathGlob.matchesAny(path, review.excludePaths());
        }
        return false;
    }

    private static boolean isBuildOrApiFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith("pom.xml") || lower.endsWith(".yml") || lower.endsWith(".yaml")
                || lower.endsWith(".sql") || lower.endsWith(".properties");
    }

    private static List<CodeSection> fixedChunks(String content, int maxChars) {
        List<CodeSection> sections = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        int window = Math.max(20, maxChars / 60);
        for (int i = 0; i < lines.length; i += window) {
            int end = Math.min(lines.length, i + window);
            StringBuilder text = new StringBuilder();
            for (int j = i; j < end; j++) {
                text.append(lines[j]).append('\n');
            }
            sections.add(new CodeSection("lines-" + (i + 1), "FILE", i + 1, end, text.toString().stripTrailing()));
        }
        return sections;
    }

    private void embedInBatches(List<CodeChunk> chunks) {
        for (int start = 0; start < chunks.size(); start += EMBED_BATCH) {
            int end = Math.min(start + EMBED_BATCH, chunks.size());
            List<CodeChunk> batch = chunks.subList(start, end);
            try {
                List<float[]> vectors = embeddings.embed(batch.stream().map(CodeChunk::text).toList());
                for (int i = 0; i < batch.size(); i++) {
                    CodeChunk chunk = batch.get(i);
                    chunks.set(start + i, new CodeChunk(chunk.id(), chunk.repository(), chunk.gitRef(),
                            chunk.commitSha(), chunk.filePath(), chunk.chunkType(), chunk.symbol(),
                            chunk.startLine(), chunk.endLine(), chunk.contentHash(), chunk.text(), vectors.get(i)));
                }
            } catch (RuntimeException e) {
                log.warn("Embedding a code batch failed, keeping lexical-only chunks: {}", e.getMessage());
            }
        }
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
