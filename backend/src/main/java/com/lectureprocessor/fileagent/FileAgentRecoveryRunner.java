package com.lectureprocessor.fileagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.lectureprocessor.fileagent.repository.FileAgentTaskRepository;

/**
 * Marks tasks that were mid-flight when the process stopped as FAILED. Pending
 * proposals are preserved: an operator can still approve them after restart.
 */
@Component
public class FileAgentRecoveryRunner {

    private static final Logger log = LoggerFactory.getLogger(FileAgentRecoveryRunner.class);

    private final FileAgentTaskRepository tasks;

    public FileAgentRecoveryRunner(FileAgentTaskRepository tasks) {
        this.tasks = tasks;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        tasks.recoverInterruptedTasks("Задача прервана перезапуском приложения; proposal можно "
                + "перегенерировать");
        log.info("File agent recovery checked interrupted tasks");
    }
}
