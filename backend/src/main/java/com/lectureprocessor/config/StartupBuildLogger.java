package com.lectureprocessor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes the build identity to the log at startup. This makes it obvious when
 * an old process is still serving requests after a rebuild.
 */
@Component
public class StartupBuildLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupBuildLogger.class);

    private final String version;
    private final String commit;
    private final String buildTime;
    private final String storageRoot;

    public StartupBuildLogger(@Value("${app.build.version:1.0.0-SNAPSHOT}") String version,
            @Value("${app.build.commit:unknown}") String commit,
            @Value("${app.build.time:unknown}") String buildTime,
            @Value("${app.storage.root:./data}") String storageRoot) {
        this.version = version;
        this.commit = commit;
        this.buildTime = buildTime;
        this.storageRoot = storageRoot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logBuild() {
        log.info("BUILD version={} commit={} builtAt={} port={} storageRoot={}", version, commit,
                buildTime, System.getProperty("server.port", "from config"), storageRoot);
    }
}
