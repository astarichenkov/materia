package com.lectureprocessor.fileagent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.lectureprocessor.fileagent.model.FileAgentTask;
import com.lectureprocessor.fileagent.repository.FileAgentTaskRepository;

/** Reuses task rows instead of introducing a separate monitoring platform. */
@Service
public class FileAgentMetricsService {

    private final FileAgentTaskRepository tasks;

    public FileAgentMetricsService(FileAgentTaskRepository tasks) {
        this.tasks = tasks;
    }

    public FileAgentMetrics metrics() {
        List<FileAgentTask> all = tasks.list(200);
        int completed = (int) all.stream()
                .filter(task -> task.status() == FileAgentTaskStatus.COMPLETED).count();
        int awaiting = (int) all.stream()
                .filter(task -> task.status() == FileAgentTaskStatus.AWAITING_APPROVAL).count();
        int failed = (int) all.stream()
                .filter(task -> task.status() == FileAgentTaskStatus.FAILED).count();
        int conflicts = (int) all.stream()
                .filter(task -> task.status() == FileAgentTaskStatus.CONFLICT).count();
        int rejected = (int) all.stream()
                .filter(task -> task.status() == FileAgentTaskStatus.REJECTED).count();
        double approvalRate = completed + rejected == 0 ? 0
                : completed / (double) (completed + rejected);
        Map<String, Integer> providers = new LinkedHashMap<>();
        for (FileAgentTask task : all) {
            if (task.provider() != null) {
                providers.merge(task.provider(), 1, Integer::sum);
            }
        }
        return new FileAgentMetrics(tasks.count(), completed, awaiting, failed, conflicts, rejected,
                tasks.sumToolCalls(), tasks.sumToolFailures(), tasks.sumFilesChanged(),
                tasks.sumRollbacks(), Math.round(approvalRate * 1000) / 1000.0, providers);
    }
}
