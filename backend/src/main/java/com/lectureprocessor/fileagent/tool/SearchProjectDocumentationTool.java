package com.lectureprocessor.fileagent.tool;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.lectureprocessor.assistant.DocSearchResult;
import com.lectureprocessor.assistant.DocumentationRetrievalService;
import com.lectureprocessor.fileagent.FileAgentProperties;
import com.lectureprocessor.mcp.McpArguments;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;

/** MCP tool: documentation RAG (README + docs) for the file-agent profile. */
@Component
public class SearchProjectDocumentationTool implements McpTool {

    private final DocumentationRetrievalService retrieval;
    private final FileAgentProperties properties;

    public SearchProjectDocumentationTool(DocumentationRetrievalService retrieval,
            FileAgentProperties properties) {
        this.retrieval = retrieval;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "search_project_documentation";
    }

    @Override
    public String profile() {
        return properties.profile();
    }

    @Override
    public String description() {
        return "Ищет фрагменты документации проекта (README, docs) через RAG. Параметры: query, "
                + "top_k (1..10). Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "query", McpSchemas.string("Поисковый запрос"),
                "top_k", McpSchemas.integer("Количество фрагментов", 1, 10)), List.of("query"));
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        String query = McpArguments.string(arguments, "query", null);
        if (query == null) {
            return McpToolResult.error("Параметр query обязателен");
        }
        try {
            int topK = McpArguments.integer(arguments, "top_k", 5, 1, 10);
            List<DocSearchResult> results = retrieval.search(query, topK, 0.0);
            if (results.isEmpty()) {
                return McpToolResult.ok("(документация не проиндексирована или ничего не найдено)");
            }
            return McpToolResult.ok(results.stream()
                    .map(result -> "[%s#%d score=%.3f]\n%s".formatted(result.chunk().sourcePath(),
                            result.chunk().sequence(), result.score(), result.chunk().text()))
                    .collect(Collectors.joining("\n\n")));
        } catch (RuntimeException e) {
            return McpToolResult.error("Документация недоступна: " + e.getMessage());
        }
    }
}
