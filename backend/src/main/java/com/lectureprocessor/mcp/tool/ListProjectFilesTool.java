package com.lectureprocessor.mcp.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.lectureprocessor.assistant.ProjectRootResolver;
import com.lectureprocessor.mcp.McpArguments;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;

/** MCP tool: bounded listing of project files. Read-only, root-confined. */
@Component
public class ListProjectFilesTool implements McpTool {

    private static final Set<String> SKIPPED =
            Set.of(".git", "target", "node_modules", "snapshots", ".idea", ".mvn");
    private static final int MAX_DEPTH = 6;

    private final ProjectRootResolver roots;

    public ListProjectFilesTool(ProjectRootResolver roots) {
        this.roots = roots;
    }

    @Override
    public String name() {
        return "list_project_files";
    }

    @Override
    public String description() {
        return "Возвращает список файлов проекта (относительные пути). Параметры: path "
                + "(подкаталог, по умолчанию корень) и limit (1..500). Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "path", McpSchemas.string("Подкаталог относительно корня проекта"),
                "limit", McpSchemas.integer("Максимум файлов", 1, 500)), List.of());
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        String relative = McpArguments.string(arguments, "path", "");
        int limit = McpArguments.integer(arguments, "limit", 200, 1, 500);
        Path root = roots.resolve();
        Path start;
        try {
            start = roots.resolveInside(relative);
        } catch (IllegalArgumentException e) {
            return McpToolResult.error(e.getMessage());
        }
        if (!Files.isDirectory(start)) {
            return McpToolResult.error("Каталог не найден: " + relative);
        }
        try (Stream<Path> stream = Files.walk(start, MAX_DEPTH)) {
            List<String> files = stream
                    .filter(Files::isRegularFile)
                    .filter(file -> !skipped(root, file))
                    .map(file -> root.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .limit(limit)
                    .toList();
            if (files.isEmpty()) {
                return McpToolResult.ok("(файлы не найдены)");
            }
            return McpToolResult.ok(String.join("\n", files));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать файлы проекта", e);
        }
    }

    private static boolean skipped(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path segment : relative) {
            if (SKIPPED.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
