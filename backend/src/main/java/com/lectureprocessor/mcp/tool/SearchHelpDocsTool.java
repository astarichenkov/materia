package com.lectureprocessor.mcp.tool;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.lectureprocessor.assistant.DocSearchResult;
import com.lectureprocessor.assistant.DocumentationRetrievalService;
import com.lectureprocessor.mcp.McpArguments;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;

/**
 * MCP tool: semantic search over the project documentation collection
 * (README + docs). Read-only.
 */
@Component
public class SearchHelpDocsTool implements McpTool {

    private final DocumentationRetrievalService retrieval;

    public SearchHelpDocsTool(DocumentationRetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    @Override
    public String name() {
        return "search_help_docs";
    }

    @Override
    public String description() {
        return "Ищет ответ в документации проекта (README, docs). Параметры: query и top_k (1..10). "
                + "Возвращает фрагменты с путём источника и оценкой. Read-only.";
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
        int topK = McpArguments.integer(arguments, "top_k", 5, 1, 10);
        List<DocSearchResult> results = retrieval.search(query, topK, 0.0);
        if (results.isEmpty()) {
            return McpToolResult.ok("(документация не проиндексирована или ничего не найдено)");
        }
        String text = results.stream()
                .map(result -> "[%s#%d score=%.3f]\n%s".formatted(
                        result.chunk().sourcePath(), result.chunk().sequence(), result.score(),
                        result.chunk().text()))
                .collect(Collectors.joining("\n\n"));
        return McpToolResult.ok(text);
    }
}
