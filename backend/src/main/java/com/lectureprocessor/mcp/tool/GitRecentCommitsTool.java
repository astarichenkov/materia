package com.lectureprocessor.mcp.tool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lectureprocessor.mcp.GitCommandRunner;
import com.lectureprocessor.mcp.GitToolSupport;
import com.lectureprocessor.mcp.McpArguments;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;

/** MCP tool: recent commits. Read-only. */
@Component
public class GitRecentCommitsTool implements McpTool {

    private final GitCommandRunner git;

    public GitRecentCommitsTool(GitCommandRunner git) {
        this.git = git;
    }

    @Override
    public String name() {
        return "git_recent_commits";
    }

    @Override
    public String description() {
        return "Возвращает последние коммиты (--oneline). Параметр count: 1..30. Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "count", McpSchemas.integer("Сколько коммитов вернуть", 1, 30)), List.of());
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        int count = McpArguments.integer(arguments, "count", 10, 1, 30);
        return GitToolSupport.toResult(
                git.run(List.of("log", "--oneline", "-n", String.valueOf(count))), "log");
    }
}
