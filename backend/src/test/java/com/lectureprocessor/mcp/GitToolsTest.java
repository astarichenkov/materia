package com.lectureprocessor.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.lectureprocessor.mcp.tool.GitCurrentBranchTool;
import com.lectureprocessor.mcp.tool.GitDiffStatTool;
import com.lectureprocessor.mcp.tool.GitRecentCommitsTool;
import com.lectureprocessor.mcp.tool.GitStatusTool;

class GitToolsTest {

    private final GitCommandRunner runner = mock(GitCommandRunner.class);

    @Test
    void currentBranchReturnsRunnerOutput() {
        when(runner.run(List.of("rev-parse", "--abbrev-ref", "HEAD")))
                .thenReturn(new GitResult(0, "feature/week7-day31\n", false, false));

        McpToolResult result = new GitCurrentBranchTool(runner).invoke(Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).isEqualTo("feature/week7-day31");
    }

    @Test
    void statusUsesShortBranchFormWithoutMutations() {
        when(runner.run(List.of("status", "--short", "--branch")))
                .thenReturn(new GitResult(0, "## main\n M file\n", false, false));

        McpToolResult result = new GitStatusTool(runner).invoke(Map.of());

        assertThat(result.text()).contains("## main");
        verify(runner).run(List.of("status", "--short", "--branch"));
    }

    @Test
    void recentCommitsClampsCount() {
        when(runner.run(List.of("log", "--oneline", "-n", "30")))
                .thenReturn(new GitResult(0, "abc123 commit\n", false, false));

        new GitRecentCommitsTool(runner).invoke(Map.of("count", 999));

        verify(runner).run(List.of("log", "--oneline", "-n", "30"));
    }

    @Test
    void diffStatNeverRequestsFullDiff() {
        when(runner.run(List.of("diff", "--stat")))
                .thenReturn(new GitResult(0, " file | 1 +\n", false, false));

        new GitDiffStatTool(runner).invoke(Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(runner).run(captor.capture());
        assertThat(captor.getValue()).containsExactly("diff", "--stat");
        assertThat(captor.getValue()).doesNotContain("push", "--hard");
    }

    @Test
    void unavailableGitIsReportedAsError() {
        when(runner.run(List.of("rev-parse", "--abbrev-ref", "HEAD")))
                .thenReturn(new GitResult(-1, "git недоступен", false, true));

        McpToolResult result = new GitCurrentBranchTool(runner).invoke(Map.of());

        assertThat(result.isError()).isTrue();
    }
}
