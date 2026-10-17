package com.lectureprocessor.fileagent.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lectureprocessor.fileagent.FileAgentMetrics;
import com.lectureprocessor.fileagent.FileAgentService;
import com.lectureprocessor.fileagent.FileWorkspace;
import com.lectureprocessor.fileagent.model.FileAgentExecution;
import com.lectureprocessor.fileagent.model.FileAgentTask;
import com.lectureprocessor.fileagent.model.FileAgentToolCall;
import com.lectureprocessor.fileagent.model.FileChangeProposal;
import com.lectureprocessor.fileagent.model.ProposedFileChange;

/**
 * REST API for the file agent. Guarded by {@code AdminSecurityFilter}: loopback
 * or authenticated admin, with CSRF for state-changing requests.
 */
@RestController
@RequestMapping("/api/file-agent")
public class FileAgentApiController {

    private final FileAgentService service;

    public FileAgentApiController(FileAgentService service) {
        this.service = service;
    }

    @PostMapping("/tasks")
    public FileAgentTask create(@RequestBody CreateTaskRequest request) {
        if (request == null || request.goal() == null) {
            throw new com.lectureprocessor.fileagent.FileAgentException("Цель не задана");
        }
        FileWorkspace workspace = FileWorkspace.from(request.workspace() == null
                ? "PROJECT_WORKSPACE" : request.workspace());
        return service.createAndStart(workspace, request.lectureId(), request.goal());
    }

    @GetMapping("/tasks")
    public List<FileAgentTask> list(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return service.list(limit);
    }

    @GetMapping("/tasks/{id}")
    public FileAgentTask get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/tasks/{id}/executions")
    public List<FileAgentExecution> executions(@PathVariable String id) {
        return service.executions(id);
    }

    @GetMapping("/tasks/{id}/trace")
    public List<FileAgentToolCall> trace(@PathVariable String id) {
        return service.toolTrace(id);
    }

    @GetMapping("/tasks/{id}/proposals")
    public List<FileChangeProposal> proposals(@PathVariable String id) {
        return service.proposals(id);
    }

    @PostMapping("/tasks/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        service.cancel(id);
        return Map.of("taskId", id, "status", "CANCELLED_REQUESTED");
    }

    @PostMapping("/tasks/{id}/regenerate")
    public FileAgentTask regenerate(@PathVariable String id) {
        return service.regenerate(id, "Перегенерировано пользователем");
    }

    @GetMapping("/proposals/{id}")
    public FileChangeProposal proposal(@PathVariable long id) {
        return service.proposal(id);
    }

    @GetMapping("/proposals/{id}/changes")
    public List<ProposedFileChange> changes(@PathVariable long id) {
        return service.changes(id);
    }

    @GetMapping(path = "/proposals/{id}/diff", produces = MediaType.TEXT_PLAIN_VALUE)
    public String diff(@PathVariable long id) {
        return service.proposal(id).diff();
    }

    @PostMapping("/proposals/{id}/approve")
    public Map<String, Object> approve(@PathVariable long id) {
        var result = service.approveAndApply(id, "admin-api");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposalId", id);
        body.put("status", result.status().name());
        body.put("applied", result.applied());
        body.put("message", result.message());
        body.put("details", result.details());
        return body;
    }

    @PostMapping("/proposals/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id,
            @RequestBody(required = false) RejectRequest request) {
        service.reject(id, request == null ? null : request.reason());
        return Map.of("proposalId", id, "status", "REJECTED");
    }

    @GetMapping("/metrics")
    public FileAgentMetrics metrics() {
        return service.metrics();
    }

    @GetMapping("/tools")
    public List<FileAgentService.ToolView> tools() {
        return service.tools();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return service.status();
    }

    /** Request body for task creation. */
    public record CreateTaskRequest(String goal, String workspace, String lectureId) {
    }

    /** Request body for rejection. */
    public record RejectRequest(String reason) {
    }
}
