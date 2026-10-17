package com.lectureprocessor.fileagent.tool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lectureprocessor.fileagent.FileAgentException;
import com.lectureprocessor.fileagent.FileAgentProperties;
import com.lectureprocessor.fileagent.workspace.FileContent;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceRef;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceResolver;
import com.lectureprocessor.fileagent.workspace.WorkspaceFileService;
import com.lectureprocessor.mcp.McpArguments;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;

/** MCP tool: bounded file read with line numbers and a content hash. */
@Component
public class ReadProjectFileTool implements McpTool {

    private final FileWorkspaceResolver resolver;
    private final WorkspaceFileService files;
    private final FileAgentProperties properties;

    public ReadProjectFileTool(FileWorkspaceResolver resolver, WorkspaceFileService files,
            FileAgentProperties properties) {
        this.resolver = resolver;
        this.files = files;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "read_project_file";
    }

    @Override
    public String profile() {
        return properties.profile();
    }

    @Override
    public String description() {
        return "Читает текстовый файл workspace с номерами строк. Параметры: path, workspace, "
                + "lecture_id, start_line, max_lines. Возвращает sha256 и total_lines. Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "path", McpSchemas.string("Путь относительно корня workspace"),
                "workspace", McpSchemas.string("PROJECT_WORKSPACE или LECTURE_WORKSPACE"),
                "lecture_id", McpSchemas.string("Идентификатор лекции"),
                "start_line", McpSchemas.integer("Первая строка (1-based)", 1, 100000),
                "max_lines", McpSchemas.integer("Максимум строк", 1, 400)), List.of("path"));
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        try {
            FileWorkspaceRef workspace = resolver.resolve(
                    McpArguments.string(arguments, "workspace", "PROJECT_WORKSPACE"),
                    McpArguments.string(arguments, "lecture_id", null));
            String path = McpArguments.string(arguments, "path", null);
            if (path == null) {
                return McpToolResult.error("Параметр path обязателен");
            }
            Integer start = arguments.containsKey("start_line")
                    ? McpArguments.integer(arguments, "start_line", 1, 1, 100000) : null;
            Integer max = arguments.containsKey("max_lines")
                    ? McpArguments.integer(arguments, "max_lines", 200, 1, 400) : null;
            FileContent content = files.read(workspace, path, start, max);
            String header = "%s\tlines=%d..%d of %d\tsha256=%s\ttruncated=%s\n"
                    .formatted(content.path(), content.firstLine(),
                            content.firstLine() + Math.max(0, content.lines().size() - 1),
                            content.totalLines(), content.sha256(), content.truncated());
            return McpToolResult.ok(header + content.numberedText());
        } catch (FileAgentException e) {
            return McpToolResult.error(e.getMessage());
        }
    }
}
