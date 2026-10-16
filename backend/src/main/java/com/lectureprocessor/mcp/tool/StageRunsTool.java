package com.lectureprocessor.mcp.tool;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.lectureprocessor.mcp.McpArguments;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;
import com.lectureprocessor.pipeline.StageCheckpoint;
import com.lectureprocessor.repository.StageCheckpointRepository;

/** MCP tool: per-stage checkpoint history (state, attempt, error). Read-only. */
@Component
public class StageRunsTool implements McpTool {

    private final StageCheckpointRepository checkpointRepository;

    public StageRunsTool(StageCheckpointRepository checkpointRepository) {
        this.checkpointRepository = checkpointRepository;
    }

    @Override
    public String name() {
        return "get_stage_runs";
    }

    @Override
    public String description() {
        return "Возвращает checkpoints стадий обработки лекции (state, attempt, error). Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "lecture_id", McpSchemas.string("ID лекции")), List.of("lecture_id"));
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        String lectureId = McpArguments.string(arguments, "lecture_id", null);
        if (lectureId == null) {
            return McpToolResult.error("Параметр lecture_id обязателен");
        }
        List<StageCheckpoint> checkpoints = checkpointRepository.findByLecture(lectureId);
        if (checkpoints.isEmpty()) {
            return McpToolResult.ok("(checkpoints не найдены)");
        }
        String text = checkpoints.stream()
                .map(cp -> "%-18s state=%s attempt=%d%s".formatted(cp.stage(), cp.state(), cp.attempt(),
                        cp.error() == null ? "" : " error=" + cp.error()))
                .collect(Collectors.joining("\n"));
        return McpToolResult.ok(text);
    }
}
