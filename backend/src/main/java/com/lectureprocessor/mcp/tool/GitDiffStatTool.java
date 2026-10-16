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

/** MCP tool: git diff summary. Read-only. */
@Component
public class GitDiffStatTool implements McpTool {

    private final GitCommandRunner git;

    public GitDiffStatTool(GitCommandRunner git) {
        this.git = git;
    }

    @Override
    public String name() {
        return "git_diff_stat";
    }

    @Override
    public String description() {
        return "Возвращает сводку изменений git diff --stat (только статистика, без диффа). "
                + "Параметр staged=true — изменения в индексе. Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "staged", McpSchemas.bool("Показать diff в индексе (--cached)")), List.of());
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        boolean staged = McpArguments.flag(arguments, "staged", false);
        List<String> args = staged
                ? List.of("diff", "--cached", "--stat")
                : List.of("diff", "--stat");
        return GitToolSupport.toResult(git.run(args), "diff --stat");
    }
}
