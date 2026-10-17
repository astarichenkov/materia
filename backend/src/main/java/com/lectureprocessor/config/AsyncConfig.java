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
     * Dedicated single-thread executor for the developer assistant: keeps the
     * HTTP request thread free while a local LLM generates an answer, serialises
     * assistant questions and bounds the queue.
     */
    @Bean(name = "assistantExecutor")
    public ThreadPoolTaskExecutor assistantExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("assistant-");
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

    /**
     * Dedicated single-thread executor for autonomous file-agent tasks. File
     * analysis and proposal generation are serialised and bounded, so a burst of
     * UI requests cannot exhaust memory or the local model.
     */
    @Bean(name = "fileAgentExecutor")
    public ThreadPoolTaskExecutor fileAgentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("file-agent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated single-thread executor for automated AI code reviews. Reviews are
     * serialised (one LLM-heavy job at a time) and the queue is bounded so a
     * burst of webhooks cannot exhaust memory. The webhook endpoint never blocks
     * on this executor.
     */
    @Bean(name = "codeReviewExecutor")
    public ThreadPoolTaskExecutor codeReviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("code-review-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
