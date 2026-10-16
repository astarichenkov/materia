package com.lectureprocessor.codereview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lectureprocessor.assistant.DocSearchResult;
import com.lectureprocessor.assistant.DocumentationRetrievalService;
import com.lectureprocessor.codereview.code.CodeIndexResult;
import com.lectureprocessor.codereview.code.CodeIndexService;
import com.lectureprocessor.codereview.code.CodeRetrievalService;
import com.lectureprocessor.codereview.code.CodeSearchResult;
import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.gitea.GiteaApiException;
import com.lectureprocessor.gitea.GiteaChangedFile;
import com.lectureprocessor.gitea.GiteaClient;
import com.lectureprocessor.gitea.GiteaPullRequest;

/**
 * Assembles the bounded, commit-consistent review context: changed file
 * contents (from Gitea at the head SHA), related code from the code RAG, and
 * documentation snippets. Truncation is always explicit and recorded so the
 * review can be marked partial rather than silently incomplete.
 */
@Component
public class CodeReviewContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewContextBuilder.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{3,}");
    private static final int MAX_CHANGED_FILE_CHARS = 8000;

    private final GiteaClient gitea;
    private final CodeIndexService codeIndex;
    private final CodeRetrievalService codeRetrieval;
    private final DocumentationRetrievalService docRetrieval;
    private final AppProperties properties;

    public CodeReviewContextBuilder(GiteaClient gitea, CodeIndexService codeIndex,
            CodeRetrievalService codeRetrieval, DocumentationRetrievalService docRetrieval,
            AppProperties properties) {
        this.gitea = gitea;
        this.codeIndex = codeIndex;
        this.codeRetrieval = codeRetrieval;
        this.docRetrieval = docRetrieval;
        this.properties = properties;
    }

    /** Diff + anchors used for validation. */
    public record DiffBundle(String diff, boolean truncated, List<String> analyzedFiles) {
    }

    public ReviewContext build(String repository, long prNumber, GiteaPullRequest pr,
            List<GiteaChangedFile> changedFiles, String rawDiff, ReviewDiffInfo diffInfo) {
        AppProperties.CodeReview config = properties.codeReview();
        String headSha = pr.headSha();

        List<String> analyzed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        boolean partial = diffInfo != null && diffInfo.truncated();

        String diff = rawDiff == null ? "" : rawDiff;
        if (diff.length() > config.maxDiffChars()) {
            diff = diff.substring(0, config.maxDiffChars()) + "\n...[diff truncated]";
            partial = true;
        }

        List<GiteaChangedFile> limited = new ArrayList<>();
        for (GiteaChangedFile file : changedFiles) {
            if (limited.size() >= config.maxFiles()) {
                skipped.add(file.filename());
                partial = true;
                continue;
            }
            limited.add(file);
            analyzed.add(file.filename());
        }

        // 1) Changed file contents from Gitea at the head SHA (full files help the model).
        List<CodeSearchResult> context = new ArrayList<>();
        int budget = config.maxContextChars();
        Set<String> addedFiles = new LinkedHashSet<>();
        for (GiteaChangedFile file : limited) {
            if ("removed".equalsIgnoreCase(file.status()) || addedFiles.contains(file.filename())) {
                continue;
            }
            try {
                String content = gitea.getFileContent(repository, file.filename(), headSha);
                if (content == null || content.isBlank()) {
                    continue;
                }
                String text = content.length() > MAX_CHANGED_FILE_CHARS
                        ? content.substring(0, MAX_CHANGED_FILE_CHARS) + "\n...[file truncated]"
                        : content;
                context.add(changedFileChunk(repository, headSha, file.filename(), text));
                budget -= text.length();
                addedFiles.add(file.filename());
            } catch (GiteaApiException e) {
                log.debug("Cannot load {}: {}", file.filename(), e.getMessage());
            }
        }

        // 2) Related code from the commit-consistent code RAG.
        boolean codeIndexAvailable = codeIndex.isIndexed(repository, headSha);
        String codeIndexNote = null;
        if (config.autoIndex()) {
            CodeIndexResult result = codeIndex.ensureIndexed(repository, headSha, false);
            codeIndexAvailable = result.reused() || result.indexed();
            if (result.unavailable()) {
                codeIndexNote = result.message();
                partial = true;
            }
        }
        if (codeIndexAvailable && budget > 500) {
            String query = buildQuery(limited, diffInfo);
            List<CodeSearchResult> related = codeRetrieval.retrieve(repository, headSha, query,
                    config.retrievalTopK(), config.minSimilarity());
            Set<String> seen = new LinkedHashSet<>();
            context.forEach(result -> seen.add(result.chunk().filePath() + "#" + result.chunk().startLine()));
            for (CodeSearchResult result : related) {
                String key = result.chunk().filePath() + "#" + result.chunk().startLine();
                if (seen.add(key) && result.chunk().text().length() <= Math.max(0, budget)) {
                    context.add(result);
                    budget -= result.chunk().text().length();
                }
            }
        }

        // 3) Documentation RAG.
        List<DocSearchResult> docs = List.of();
        try {
            docs = docRetrieval.search(buildQuery(limited, diffInfo));
        } catch (RuntimeException e) {
            log.debug("Documentation retrieval unavailable: {}", e.getMessage());
        }

        return new ReviewContext(repository, prNumber, pr.title(), pr.baseSha(), headSha, diff, limited,
                context, docs, partial, analyzed, skipped, codeIndexAvailable, codeIndexNote);
    }

    private static CodeSearchResult changedFileChunk(String repository, String sha, String path, String text) {
        int lines = (int) text.lines().count();
        var chunk = new com.lectureprocessor.codereview.code.CodeChunk(null, repository, sha, sha, path,
                "CHANGED_FILE", path, 1, Math.max(1, lines), null, text, null);
        return new CodeSearchResult(chunk, 1.0, 0, 1.0);
    }

    /** Query = changed paths + identifiers extracted from added diff lines. */
    static String buildQuery(List<GiteaChangedFile> files, ReviewDiffInfo diffInfo) {
        StringBuilder query = new StringBuilder();
        files.forEach(file -> query.append(file.filename()).append(' '));
        if (diffInfo != null) {
            for (String identifier : diffInfo.identifiers()) {
                query.append(identifier).append(' ');
            }
        }
        return query.toString().strip();
    }

    /** Extracts identifiers from added lines for retrieval (bounded). */
    public static Set<String> extractIdentifiers(String diff) {
        Set<String> identifiers = new LinkedHashSet<>();
        if (diff == null) {
            return identifiers;
        }
        for (String line : diff.split("\n")) {
            if (!line.startsWith("+") || line.startsWith("+++")) {
                continue;
            }
            Matcher matcher = IDENTIFIER.matcher(line);
            while (matcher.find() && identifiers.size() < 80) {
                identifiers.add(matcher.group());
            }
        }
        return identifiers;
    }

    public static Map<String, Set<Integer>> anchors(List<com.lectureprocessor.codereview.diff.DiffFile> files) {
        Map<String, Set<Integer>> anchors = new LinkedHashMap<>();
        for (var file : files) {
            anchors.put(ReviewValidator.normalizePath(file.path()), file.changedLines());
        }
        return anchors;
    }

    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
