package com.lectureprocessor.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.lectureprocessor.pipeline.PipelineCoordinator;

/**
 * Submits lecture processing to the bounded executor. Duplicate-run protection
 * is enforced by {@link PipelineCoordinator} through a persisted DB lease plus
 * an in-memory token registry.
 */
@Service
public class LectureJobService {

    private static final Logger log = LoggerFactory.getLogger(LectureJobService.class);

    private final ThreadPoolTaskExecutor executor;
    private final PipelineCoordinator coordinator;

    public LectureJobService(ThreadPoolTaskExecutor lectureJobExecutor, PipelineCoordinator coordinator) {
        this.executor = lectureJobExecutor;
        this.coordinator = coordinator;
    }

    public boolean submit(String lectureId) {
        if (coordinator.isRunning(lectureId)) {
            log.debug("Lecture {} is already being processed", lectureId);
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    coordinator.run(lectureId);
                } catch (Exception e) {
                    log.error("Lecture {} processing ended with error: {}", lectureId, e.getMessage());
                }
            });
            return true;
        } catch (TaskRejectedException e) {
            throw new JobQueueFullException("Очередь обработки переполнена, попробуйте позже");
        }
    }

    public boolean isRunning(String lectureId) {
        return coordinator.isRunning(lectureId);
    }

    /** Requests cancellation; returns false when nothing is running. */
    public boolean cancel(String lectureId) {
        return coordinator.cancel(lectureId);
    }
}
