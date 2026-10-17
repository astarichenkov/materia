package com.lectureprocessor.fileagent.tool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lectureprocessor.fileagent.FileAgentProperties;
import com.lectureprocessor.fileagent.FileAgentException;
import com.lectureprocessor.fileagent.workspace.FileSearchHit;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceRef;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceResolver;
import com.lectureprocessor.fileagent.workspace.WorkspaceFileService;
import com.lectureprocessor.mcp.McpArguments;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;

/** MCP tool: lexical file search inside one explicit workspace. Read-only. */
@Component
public class SearchProjectFilesTool implements McpTool {

    private final FileWorkspaceResolver resolver;
    private final WorkspaceFileService files;
    private final FileAgentProperties properties;

    public SearchProjectFilesTool(FileWorkspaceResolver resolver, WorkspaceFileService files,
            FileAgentProperties properties) {
        this.resolver = resolver;
        this.files = files;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "search_project_files";
    }

    @Override
    public String profile() {
        return properties.profile();
    }

    @Override
    public String description() {
        return "Ищет файлы проекта/лекции по имени и содержимому. Параметры: query, workspace "
                + "(PROJECT_WORKSPACE|LECTURE_WORKSPACE), lecture_id (для лекции), mask (например *.md), "
                + "limit (1..30). Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "query", McpSchemas.string("Поисковый запрос (имя файла, символ, фраза)"),
                "workspace", McpSchemas.string("PROJECT_WORKSPACE или LECTURE_WORKSPACE"),
                "lecture_id", McpSchemas.string("Идентификатор лекции для LECTURE_WORKSPACE"),
                "mask", McpSchemas.string("Маска файла, например *.md"),
                "limit", McpSchemas.integer("Максимум результатов", 1, 30)), List.of("query"));
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        try {
            FileWorkspaceRef workspace = resolver.resolve(
                    McpArguments.string(arguments, "workspace", "PROJECT_WORKSPACE"),
                    McpArguments.string(arguments, "lecture_id", null));
            String query = McpArguments.string(arguments, "query", null);
            if (query == null) {
                return McpToolResult.error("Параметр query обязателен");
            }
            int limit = McpArguments.integer(arguments, "limit", 10, 1, 30);
            List<FileSearchHit> hits = files.searchFiles(workspace, query,
                    McpArguments.string(arguments, "mask", null), limit);
            if (hits.isEmpty()) {
                return McpToolResult.ok("(файлы не найдены)");
            }
            StringBuilder text = new StringBuilder();
            for (FileSearchHit hit : hits) {
                text.append(hit.path()).append("\tscore=").append(hit.score())
                        .append("\tsize=").append(hit.sizeBytes());
                if (hit.match() != null && !hit.match().isBlank()) {
                    text.append("\tmatch=").append(hit.match().replace('\n', ' '));
                }
                text.append('\n');
            }
            return McpToolResult.ok(text.toString());
        } catch (FileAgentException e) {
            return McpToolResult.error(e.getMessage());
        }
    }
}
