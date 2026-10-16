package com.lectureprocessor.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.assistant.ProjectRootResolver;
import com.lectureprocessor.support.TestProperties;

/**
 * Runs real git commands in read-only mode against the checkout. This verifies
 * the process invocation, timeout handling and output capture end to end.
 */
class GitCommandRunnerTest {

    private final GitCommandRunner runner = new GitCommandRunner(
            new ProjectRootResolver(TestProperties.defaults(".")),
            TestProperties.defaults("."));

    @Test
    void resolvesCurrentBranchOfThisRepository() {
        GitResult result = runner.run(List.of("rev-parse", "--abbrev-ref", "HEAD"));

        assertThat(result.gitUnavailable()).isFalse();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isNotBlank();
    }

    @Test
    void rejectsEmptyArgumentList() {
        GitResult result = runner.run(List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.output()).contains("Некорректные аргументы");
    }
}
