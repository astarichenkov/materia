package com.lectureprocessor.fileagent.tool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lectureprocessor.fileagent.FileAgentException;
import com.lectureprocessor.fileagent.FileAgentProperties;
import com.lectureprocessor.fileagent.workspace.FileMetadata;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceRef;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceResolver;
import com.lectureprocessor.fileagent.workspace.WorkspaceFileService;
import com.lectureprocessor.mcp.McpArguments;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;

/** MCP tool: non-content metadata for one workspace file. */
@Component
public class GetFileMetadataTool implements McpTool {

    private final FileWorkspaceResolver resolver;
    private final WorkspaceFileService files;
    private final FileAgentProperties properties;

    public GetFileMetadataTool(FileWorkspaceResolver resolver, WorkspaceFileService files,
            FileAgentProperties properties) {
        this.resolver = resolver;
        this.files = files;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "get_file_metadata";
    }

    @Override
    public String profile() {
        return properties.profile();
    }

    @Override
    public String description() {
        return "Возвращает размер, тип, mtime, sha256 и флаги защиты файла workspace. "
                + "Параметры: path, workspace, lecture_id. Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "path", McpSchemas.string("Путь относительно корня workspace"),
                "workspace", McpSchemas.string("PROJECT_WORKSPACE или LECTURE_WORKSPACE"),
                "lecture_id", McpSchemas.string("Идентификатор лекции")), List.of("path"));
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
            FileMetadata metadata = files.metadata(workspace, path);
            return McpToolResult.ok("""
                    path=%s
                    workspace=%s
                    size=%d
                    content_type=%s
                    last_modified=%s
                    sha256=%s
                    protected=%s
                    write_forbidden=%s
                    """.formatted(metadata.path(), metadata.workspace(), metadata.sizeBytes(),
                    metadata.contentType(), metadata.lastModified(), metadata.sha256(),
                    metadata.protectedFile(), metadata.readOnly()));
        } catch (FileAgentException e) {
            return McpToolResult.error(e.getMessage());
        }
    }
}
