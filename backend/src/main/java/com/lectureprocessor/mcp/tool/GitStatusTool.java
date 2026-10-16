package com.lectureprocessor.mcp.tool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lectureprocessor.mcp.GitCommandRunner;
import com.lectureprocessor.mcp.GitToolSupport;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;

/** MCP tool: short git status (branch + changed files). Read-only. */
@Component
public class GitStatusTool implements McpTool {

    private final GitCommandRunner git;

    public GitStatusTool(GitCommandRunner git) {
        this.git = git;
    }

    @Override
    public String name() {
        return "git_status";
    }

    @Override
    public String description() {
        return "Возвращает краткий git status (--short --branch): ветка и изменённые файлы. Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.emptyObject();
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        return GitToolSupport.toResult(git.run(List.of("status", "--short", "--branch")), "status");
    }
}
