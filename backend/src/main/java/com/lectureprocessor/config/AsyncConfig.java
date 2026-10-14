package com.lectureprocessor.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded executor for lecture processing jobs. Bounded queue + AbortPolicy
 * prevents unbounded memory growth when many lectures are queued.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "lectureJobExecutor")
    public ThreadPoolTaskExecutor lectureJobExecutor(AppProperties properties) {
        AppProperties.Processing processing = properties.processing();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(processing.workerThreads());
        executor.setMaxPoolSize(processing.workerThreads());
        executor.setQueueCapacity(processing.queueCapacity());
        executor.setThreadNamePrefix("lecture-job-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated single-thread executor for benchmark runs: keeps the HTTP thread
     * free, serialises LLM-heavy benchmarks and avoids competing with lecture
     * processing jobs for the same model.
     */
    @Bean(name = "benchmarkExecutor")
    public ThreadPoolTaskExecutor benchmarkExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("benchmark-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
