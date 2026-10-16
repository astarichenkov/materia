package com.lectureprocessor.mcp;

/** Formats git command outcomes into MCP tool results. */
public final class GitToolSupport {

    private GitToolSupport() {
    }

    public static McpToolResult toResult(GitResult result, String action) {
        if (result.gitUnavailable()) {
            return McpToolResult.error(result.output());
        }
        if (result.timedOut()) {
            return McpToolResult.error(result.output());
        }
        if (!result.ok()) {
            String detail = result.output().isBlank() ? "(без вывода)" : result.output();
            return McpToolResult.error("git " + action + " завершился с кодом " + result.exitCode()
                    + ": " + detail);
        }
        String output = result.output().strip();
        if (output.isBlank()) {
            return McpToolResult.ok("(" + action + ": пустой вывод)");
        }
        return McpToolResult.ok(output);
    }
}
