package com.lectureprocessor.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lectureprocessor.assistant.ProjectRootResolver;
import com.lectureprocessor.config.AppProperties;

/**
 * Runs read-only git commands for the MCP tools. Commands are passed as an
 * argument vector (never a shell), run in the project root, bounded by a
 * timeout and a maximum captured output size. Callers provide fixed argument
 * lists; no model-provided string is ever executed as a command.
 */
@Component
public class GitCommandRunner {

    private static final Logger log = LoggerFactory.getLogger(GitCommandRunner.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ARGUMENTS = 12;

    private final ProjectRootResolver roots;
    private final AppProperties properties;

    public GitCommandRunner(ProjectRootResolver roots, AppProperties properties) {
        this.roots = roots;
        this.properties = properties;
    }

    public GitResult run(List<String> gitArguments) {
        if (gitArguments == null || gitArguments.isEmpty() || gitArguments.size() > MAX_ARGUMENTS) {
            return new GitResult(-1, "Некорректные аргументы git", false, false);
        }
        int maxChars = Math.max(1000, properties.mcp().maxOutputChars());
        List<String> command = new ArrayList<>(gitArguments.size() + 1);
        command.add("git");
        command.addAll(gitArguments);

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(roots.resolve().toFile())
                .redirectErrorStream(true);
        try {
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> drain(process, output, maxChars), "mcp-git-reader");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1000);
                return new GitResult(-1, "git не ответил за " + TIMEOUT.toSeconds() + " с", true, false);
            }
            reader.join(2000);
            return new GitResult(process.exitValue(), output.toString().strip(), false, false);
        } catch (IOException e) {
            log.debug("git is unavailable: {}", e.getMessage());
            return new GitResult(-1, "git недоступен: " + e.getMessage(), false, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitResult(-1, "Выполнение git прервано", false, false);
        }
    }

    private static void drain(Process process, StringBuilder output, int maxChars) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < maxChars) {
                    output.append(line).append('\n');
                }
            }
        } catch (IOException e) {
            // The process was killed or the pipe closed; nothing to add.
        }
    }
}
