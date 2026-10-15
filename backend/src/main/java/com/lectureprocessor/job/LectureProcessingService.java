package com.lectureprocessor.job;

import org.springframework.stereotype.Service;

import com.lectureprocessor.pipeline.PipelineCoordinator;

/**
 * Runs the full resumable pipeline for one lecture. Retained as the application
 * entry point (used by the async job executor and tests); all orchestration now
 * lives in {@link PipelineCoordinator}.
 */
@Service
public class LectureProcessingService {

    private final PipelineCoordinator coordinator;

    public LectureProcessingService(PipelineCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void process(String lectureId) {
        coordinator.run(lectureId);
    }
}
