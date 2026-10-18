package com.lectureprocessor.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;

/**
 * Startup recovery. Because this is a single-instance deployment, no pipeline
 * job can still be running after a restart: every RUNNING {@code pipeline_job}
 * becomes INTERRUPTED (retryable) and its lecture is marked INTERRUPTED.
 *
 * <p>Completed stage checkpoints are preserved, so resuming only re-runs the
 * unfinished stages instead of the whole pipeline.</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StaleJobRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaleJobRecovery.class);

    private final PipelineJobRepository jobRepository;
    private final LectureRepository lectureRepository;
    private final com.lectureprocessor.repository.StageCheckpointRepository checkpointRepository;

    public StaleJobRecovery(PipelineJobRepository jobRepository, LectureRepository lectureRepository,
            com.lectureprocessor.repository.StageCheckpointRepository checkpointRepository) {
        this.jobRepository = jobRepository;
        this.lectureRepository = lectureRepository;
        this.checkpointRepository = checkpointRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        int interrupted = jobRepository.interruptAllRunning();
        if (interrupted > 0) {
            log.warn("Recovered {} interrupted pipeline job(s)", interrupted);
        }
        // A stage checkpoint must never stay RUNNING after a restart: the worker
        // that owned it does not exist anymore.
        int stages = checkpointRepository.interruptAllRunning();
        if (stages > 0) {
            log.warn("Marked {} interrupted stage checkpoint(s)", stages);
        }
        lectureRepository.findAll().stream()
                .filter(lecture -> !lecture.status().isTerminal())
                .forEach(lecture -> {
                    log.warn("Lecture {} was interrupted in status {}; marking INTERRUPTED",
                            lecture.id(), lecture.status());
                    lectureRepository.save(new com.lectureprocessor.domain.Lecture(
                            lecture.id(), lecture.originalFilename(), lecture.storedFilename(),
                            lecture.sizeBytes(), lecture.contentType(), JobStatus.INTERRUPTED,
                            "Обработка была прервана (перезапуск приложения). Можно возобновить.",
                            lecture.durationMs(), lecture.cleanTranscript(), lecture.extractSlides(),
                            lecture.rawTranscriptPath(), lecture.cleanedText(), lecture.notesMarkdown(),
                            lecture.docxPath(), lecture.createdAt(), java.time.Instant.now()));
                });
    }
}
