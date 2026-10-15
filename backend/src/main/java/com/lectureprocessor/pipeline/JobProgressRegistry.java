package com.lectureprocessor.pipeline;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Component;

/**
 * In-memory view of live job progress. Not the source of truth (checkpoints are)
 * — it only enriches the UI between persisted stage transitions.
 */
@Component
public class JobProgressRegistry {

    private final Map<String, JobProgress> progress = new ConcurrentHashMap<>();

    public void set(JobProgress value) {
        progress.put(value.lectureId(), value);
    }

    public void update(String lectureId, UnaryOperator<JobProgress> mutator) {
        progress.compute(lectureId, (id, current) -> mutator.apply(current));
    }

    public Optional<JobProgress> get(String lectureId) {
        return Optional.ofNullable(progress.get(lectureId));
    }

    public void remove(String lectureId) {
        progress.remove(lectureId);
    }
}
