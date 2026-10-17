package com.lectureprocessor.fileagent;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Day 34 autonomous file agent.
 *
 * <p>Kept separate from {@code AppProperties} so existing tests and components
 * that construct {@code AppProperties} directly remain unchanged, mirroring the
 * Day 33 {@code SupportProperties} approach.</p>
 *
 * @param enabled                  master switch for the file agent surface
 * @param projectRoot              explicit project workspace root (blank = detect)
 * @param lectureWorkspaceEnabled  allow LECTURE_WORKSPACE tasks at all
 * @param mcpReadKey              key required for the external file-agent MCP
 *                                profile; blank disables that profile externally
 * @param allowedExtensions       text file extensions the agent may read/propose
 * @param writeForbiddenGlobs     project files the agent may read but never modify
 * @param protectedGlobs          files that are never readable or writable
 * @param maxFileBytes            maximum size of one file read or proposed
 * @param maxFilesPerTask         maximum distinct files read into context
 * @param maxSearchResults        maximum search results per tool call
 * @param maxReadLines            maximum lines returned by one read
 * @param maxDepth                maximum relative path depth
 * @param maxSteps                maximum orchestration steps
 * @param maxToolCalls            maximum tool invocations per task
 * @param maxContextChars         maximum characters of gathered context
 * @param maxPatchBytes           maximum total proposed content size
 * @param maxProposals            maximum files in one proposal
 * @param timeout                 hard wall-clock timeout for one task
 * @param goalChars               maximum natural-language goal length
 * @param mcpProfile              profile name exposed by file tools
 */
@ConfigurationProperties(prefix = "app.file-agent")
public record FileAgentProperties(
        boolean enabled,
        String projectRoot,
        boolean lectureWorkspaceEnabled,
        String mcpReadKey,
        List<String> allowedExtensions,
        List<String> writeForbiddenGlobs,
        List<String> protectedGlobs,
        long maxFileBytes,
        int maxFilesPerTask,
        int maxSearchResults,
        int maxReadLines,
        int maxDepth,
        int maxSteps,
        int maxToolCalls,
        int maxContextChars,
        int maxPatchBytes,
        int maxProposals,
        Duration timeout,
        int goalChars,
        String mcpProfile) {

    public static final String PROFILE = "file-agent-read";

    /** Safe defaults used by tests and as configuration fallback. */
    public static FileAgentProperties defaults(String projectRoot) {
        return new FileAgentProperties(true, projectRoot, true, "",
                List.of("java", "md", "txt", "yml", "yaml", "xml", "sql", "properties",
                        "html", "css", "js", "json", "sh", "ps1", "gradle"),
                List.of("AGENTS.md", "pom.xml", "mvnw", "mvnw.cmd", "**/pom.xml",
                        "**/mvnw", "**/mvnw.cmd", ".github/**", "**/.github/**",
                        "backend/src/main/resources/application.yml",
                        "**/application.yml"),
                List.of("**/.env", "**/.env.*", "**/*.key", "**/*.pem", "**/*.p12",
                        "**/*.jks", "**/*.keystore", "**/id_rsa*", "**/.git/**",
                        "**/.secrets/**", "**/master.key", "**/*credential*",
                        "**/data/**", "**/target/**", "**/build/**", "**/node_modules/**",
                        "**/*.db", "**/*.sqlite*", "**/*.class", "**/*.jar", "**/*.docx",
                        "**/*.jpg", "**/*.jpeg", "**/*.png", "**/*.gif", "**/*.webp",
                        "**/*.zip", "**/*.pdf"),
                400_000L, 8, 30, 400, 12, 10, 20, 48_000, 140_000, 6,
                Duration.ofMinutes(5), 4_000, PROFILE);
    }

    public String profile() {
        return mcpProfile == null || mcpProfile.isBlank() ? PROFILE : mcpProfile;
    }

    public List<String> extensions() {
        return allowedExtensions == null ? List.of() : allowedExtensions;
    }

    public List<String> protectedPatterns() {
        return protectedGlobs == null ? List.of() : protectedGlobs;
    }

    public List<String> writeForbiddenPatterns() {
        return writeForbiddenGlobs == null ? List.of() : writeForbiddenGlobs;
    }
}
