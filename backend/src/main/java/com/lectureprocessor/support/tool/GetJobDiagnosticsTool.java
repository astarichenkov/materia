package com.lectureprocessor.support.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.mcp.McpArguments;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;
import com.lectureprocessor.pipeline.PipelineJob;
import com.lectureprocessor.repository.DownloadStateRepository;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.repository.StageCheckpointRepository;
import com.lectureprocessor.repository.TimingRepository;
import com.lectureprocessor.support.SecretRedactor;

import tools.jackson.databind.ObjectMapper;

/**
 * MCP tool: consolidated, read-only diagnostics for one lecture/job.
 *
 * <p>Reports the pipeline job state, the last stage, persisted errors, retry
 * count, checkpoint availability, download telemetry and stage timings. It never
 * invents a cause: when no rows exist the output says so explicitly.</p>
 */
@Component
public class GetJobDiagnosticsTool implements McpTool {

    private final LectureRepository lectures;
    private final PipelineJobRepository jobs;
    private final StageCheckpointRepository checkpoints;
    private final DownloadStateRepository downloads;
    private final TimingRepository timings;
    private final ObjectMapper objectMapper;

    public GetJobDiagnosticsTool(LectureRepository lectures, PipelineJobRepository jobs,
            StageCheckpointRepository checkpoints, DownloadStateRepository downloads,
            TimingRepository timings, ObjectMapper objectMapper) {
        this.lectures = lectures;
        this.jobs = jobs;
        this.checkpoints = checkpoints;
        this.downloads = downloads;
        this.timings = timings;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "get_job_diagnostics";
    }

    @Override
    public String description() {
        return "Возвращает консолидированную диагностику обработки лекции: статус job, последняя стадия, "
                + "ошибки, число попыток, checkpoints, тайминги и доступность артефактов. "
                + "Параметры: lecture_id или job_id. Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "lecture_id", McpSchemas.string("ID лекции"),
                "job_id", McpSchemas.string("ID задачи (совпадает с ID лекции)")), List.of());
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        String id = McpArguments.string(arguments, "lecture_id",
                McpArguments.string(arguments, "job_id", null));
        if (id == null) {
            return McpToolResult.error("Нужен параметр lecture_id или job_id");
        }
        Lecture lecture = lectures.findById(id).orElse(null);
        PipelineJob job = jobs.find(id).orElse(null);
        var checkpointRows = checkpoints.findByLecture(id);
        var download = downloads.find(id).orElse(null);
        var timing = timings.findByLecture(id);

        if (lecture == null && job == null && checkpointRows.isEmpty()) {
            return McpToolResult.ok(objectMapper.writeValueAsString(Map.of(
                    "lectureId", id,
                    "available", false,
                    "message", "Диагностических данных по этой задаче нет: лекция и job не найдены.")));
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("lectureId", id);
        view.put("available", true);
        if (lecture != null) {
            view.put("lectureStatus", lecture.status().name());
            view.put("lectureError", SecretRedactor.redact(lecture.errorMessage()));
            view.put("durationMs", lecture.durationMs());
            view.put("hasTranscript", lecture.rawTranscriptPath() != null);
            view.put("hasCleanedText", lecture.cleanedText() != null && !lecture.cleanedText().isBlank());
            view.put("hasNotes", lecture.notesMarkdown() != null && !lecture.notesMarkdown().isBlank());
            view.put("hasDocx", lecture.docxPath() != null);
        }
        if (job != null) {
            view.put("jobState", job.state().name());
            view.put("currentStage", job.currentStage() == null ? null : job.currentStage().name());
            view.put("attempts", job.attempts());
            view.put("jobError", SecretRedactor.redact(job.error()));
            view.put("jobCreatedAt", job.createdAt().toString());
            view.put("jobUpdatedAt", job.updatedAt().toString());
        } else {
            view.put("jobState", null);
            view.put("message", "Запись pipeline_job отсутствует — задача ещё не запускалась.");
        }
        if (download != null) {
            Map<String, Object> dl = new LinkedHashMap<>();
            dl.put("status", download.status());
            dl.put("percent", download.percent());
            dl.put("error", SecretRedactor.redact(download.error()));
            view.put("download", dl);
        }
        if (!timing.isEmpty()) {
            view.put("timings", timing);
        }
        List<Map<String, Object>> stageViews = new ArrayList<>();
        for (var cp : checkpointRows) {
            Map<String, Object> stage = new LinkedHashMap<>();
            stage.put("stage", cp.stage().name());
            stage.put("state", cp.state().name());
            stage.put("attempt", cp.attempt());
            stage.put("startedAt", cp.startedAt() == null ? null : cp.startedAt().toString());
            stage.put("finishedAt", cp.finishedAt() == null ? null : cp.finishedAt().toString());
            stage.put("error", SecretRedactor.redact(cp.error()));
            stage.put("artifactCount", cp.manifest() == null ? 0 : cp.manifest().artifacts().size());
            stageViews.add(stage);
        }
        view.put("stages", stageViews);

        List<String> warnings = new ArrayList<>();
        checkpointRows.stream()
                .filter(cp -> cp.error() != null && !cp.error().isBlank())
                .forEach(cp -> warnings.add(cp.stage().name() + ": " + SecretRedactor.redact(cp.error())));
        if (lecture != null && lecture.errorMessage() != null && !lecture.errorMessage().isBlank()) {
            warnings.add("lecture: " + SecretRedactor.redact(lecture.errorMessage()));
        }
        view.put("warnings", warnings);
        return McpToolResult.ok(objectMapper.writeValueAsString(view));
    }
}
