package com.lectureprocessor.fileagent.tool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lectureprocessor.fileagent.FileAgentException;
import com.lectureprocessor.fileagent.FileAgentProperties;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceRef;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceResolver;
import com.lectureprocessor.fileagent.workspace.SymbolHit;
import com.lectureprocessor.fileagent.workspace.WorkspaceFileService;
import com.lectureprocessor.mcp.McpArguments;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;

/** MCP tool: local source-symbol search (classes, methods, references). */
@Component
public class SearchCodeSymbolsTool implements McpTool {

    private final FileWorkspaceResolver resolver;
    private final WorkspaceFileService files;
    private final FileAgentProperties properties;

    public SearchCodeSymbolsTool(FileWorkspaceResolver resolver, WorkspaceFileService files,
            FileAgentProperties properties) {
        this.resolver = resolver;
        this.files = files;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "search_code_symbols";
    }

    @Override
    public String profile() {
        return properties.profile();
    }

    @Override
    public String description() {
        return "Ищет классы, методы и ссылки на символ в исходниках workspace. Параметры: symbol, "
                + "workspace, lecture_id, limit (1..30). Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "symbol", McpSchemas.string("Имя класса, метода или идентификатора"),
                "workspace", McpSchemas.string("PROJECT_WORKSPACE или LECTURE_WORKSPACE"),
                "lecture_id", McpSchemas.string("Идентификатор лекции"),
                "limit", McpSchemas.integer("Максимум результатов", 1, 30)), List.of("symbol"));
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        try {
            FileWorkspaceRef workspace = resolver.resolve(
                    McpArguments.string(arguments, "workspace", "PROJECT_WORKSPACE"),
                    McpArguments.string(arguments, "lecture_id", null));
            String symbol = McpArguments.string(arguments, "symbol", null);
            if (symbol == null) {
                return McpToolResult.error("Параметр symbol обязателен");
            }
            int limit = McpArguments.integer(arguments, "limit", 10, 1, 30);
            List<SymbolHit> hits = files.searchSymbols(workspace, symbol, limit);
            if (hits.isEmpty()) {
                return McpToolResult.ok("(символ не найден)");
            }
            StringBuilder text = new StringBuilder();
            for (SymbolHit hit : hits) {
                text.append(hit.path()).append(':').append(hit.startLine()).append('-')
                        .append(hit.endLine()).append('\t').append(hit.kind()).append('\t')
                        .append(hit.symbol()).append("\t:: ")
                        .append(hit.snippet().replace('\n', ' ')).append('\n');
            }
            return McpToolResult.ok(text.toString());
        } catch (FileAgentException e) {
            return McpToolResult.error(e.getMessage());
        }
    }
}
