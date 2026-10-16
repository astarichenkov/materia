package com.lectureprocessor.mcp.tool;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.lectureprocessor.lecture.LectureNotFoundException;
import com.lectureprocessor.mcp.McpArguments;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;
import com.lectureprocessor.pipeline.JobStatusService;
import com.lectureprocessor.pipeline.JobStatusView;
import com.lectureprocessor.pipeline.PipelineJob;
import com.lectureprocessor.repository.PipelineJobRepository;

/**
 * MCP tool: read-only lecture pipeline status. Without {@code lecture_id} it
 * lists recent jobs; with it, returns the stage-by-stage view.
 */
@Component
public class JobStatusTool implements McpTool {

    private final JobStatusService jobStatusService;
    private final PipelineJobRepository jobRepository;

    public JobStatusTool(JobStatusService jobStatusService, PipelineJobRepository jobRepository) {
        this.jobStatusService = jobStatusService;
        this.jobRepository = jobRepository;
    }

    @Override
    public String name() {
        return "get_job_status";
    }

    @Override
    public String description() {
        return "Возвращает статус обработки лекций. Без lecture_id — последние задачи; "
                + "с lecture_id — этапы, прогресс и предупреждения. Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "lecture_id", McpSchemas.string("ID лекции; если не задан — список задач")), List.of());
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        String lectureId = McpArguments.string(arguments, "lecture_id", null);
        if (lectureId == null) {
            List<PipelineJob> jobs = jobRepository.findAll();
            if (jobs.isEmpty()) {
                return McpToolResult.ok("(задач нет)");
            }
            String text = jobs.stream()
                    .limit(50)
                    .map(job -> "%s | state=%s | stage=%s | attempts=%d".formatted(
                            job.lectureId(), job.state(), job.currentStage(), job.attempts()))
                    .collect(Collectors.joining("\n"));
            return McpToolResult.ok(text);
        }
        try {
            JobStatusView view = jobStatusService.view(lectureId);
            String stages = view.stages().stream()
                    .map(stage -> "  %-18s %s%s".formatted(stage.stage(), stage.state(),
                            stage.error() == null ? "" : " (" + stage.error() + ")"))
                    .collect(Collectors.joining("\n"));
            String text = """
                    lecture=%s
                    status=%s jobState=%s stage=%s (%d/%d, %d%%)
                    message=%s
                    stages:
                    %s
                    """.formatted(view.lectureId(), view.status(), view.jobState(), view.currentStage(),
                    view.stageIndex(), view.totalStages(), view.progressPercent(),
                    view.message() == null ? "" : view.message(), stages);
            return McpToolResult.ok(text.strip());
        } catch (LectureNotFoundException e) {
            return McpToolResult.error("Лекция не найдена: " + lectureId);
        }
    }
}
