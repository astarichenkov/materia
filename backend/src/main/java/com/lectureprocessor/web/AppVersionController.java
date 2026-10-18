package com.lectureprocessor.web;

import java.lang.management.ManagementFactory;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only build/runtime identification. Deliberately contains no credentials,
 * storage paths or other installation-private data.
 */
@RestController
public class AppVersionController {

    private final Environment environment;
    private final ApplicationContext context;
    private final String version;
    private final String commit;
    private final String buildTime;

    public AppVersionController(Environment environment, ApplicationContext context,
            @Value("${app.build.version:1.0.0-SNAPSHOT}") String version,
            @Value("${app.build.commit:unknown}") String commit,
            @Value("${app.build.time:unknown}") String buildTime) {
        this.environment = environment;
        this.context = context;
        this.version = version;
        this.commit = commit;
        this.buildTime = buildTime;
    }

    @GetMapping("/api/app/version")
    public AppVersion version() {
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        String shortCommit = commit != null && commit.length() > 10 ? commit.substring(0, 10) : commit;
        return new AppVersion(
                environment.getProperty("spring.application.name", "lecture-processor"),
                safe(version),
                safe(commit),
                safe(shortCommit),
                safe(buildTime),
                System.getProperty("java.version", "unknown"),
                System.getProperty("java.vendor", "unknown"),
                pid.contains("@") ? pid.substring(0, pid.indexOf('@')) : pid,
                Instant.ofEpochMilli(context.getStartupDate()).toString());
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    public record AppVersion(String application, String version, String commit, String commitShort,
            String buildTime, String javaVersion, String javaVendor, String pid, String startedAt) {
    }
}
