package com.lectureprocessor.mcp.tool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lectureprocessor.mcp.GitCommandRunner;
import com.lectureprocessor.mcp.GitResult;
import com.lectureprocessor.mcp.GitToolSupport;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;

/** MCP tool: current git branch of the project. */
@Component
public class GitCurrentBranchTool implements McpTool {

    private final GitCommandRunner git;

    public GitCurrentBranchTool(GitCommandRunner git) {
        this.git = git;
    }

    @Override
    public String name() {
        return "git_current_branch";
    }

    @Override
    public String description() {
        return "Возвращает текущую git-ветку проекта (или 'HEAD' при detached HEAD). Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.emptyObject();
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        GitResult result = git.run(List.of("rev-parse", "--abbrev-ref", "HEAD"));
        return GitToolSupport.toResult(result, "rev-parse");
    }
}
