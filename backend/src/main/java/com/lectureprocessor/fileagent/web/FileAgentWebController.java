package com.lectureprocessor.fileagent.web;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.lectureprocessor.fileagent.FileAgentService;
import com.lectureprocessor.fileagent.FileWorkspace;
import com.lectureprocessor.fileagent.model.FileAgentTask;
import com.lectureprocessor.fileagent.model.FileChangeProposal;
import com.lectureprocessor.fileagent.model.ProposedFileChange;
import com.lectureprocessor.web.security.AdminAccessService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Thymeleaf UI for the autonomous file agent. Every state-changing form carries
 * a CSRF token; the whole surface is protected by {@code AdminSecurityFilter}.
 */
@Controller
public class FileAgentWebController {

    private final FileAgentService service;
    private final AdminAccessService access;

    public FileAgentWebController(FileAgentService service, AdminAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/file-agent")
    public String page(@RequestParam(name = "taskId", required = false) String taskId,
            HttpServletRequest request, Model model) {
        List<FileAgentTask> tasks = service.list(50);
        FileAgentTask selected = null;
        if (taskId != null && !taskId.isBlank()) {
            try {
                selected = service.get(taskId);
            } catch (RuntimeException e) {
                model.addAttribute("error", e.getMessage());
            }
        } else if (!tasks.isEmpty()) {
            selected = tasks.getFirst();
        }
        model.addAttribute("tasks", tasks);
        model.addAttribute("selected", selected);
        model.addAttribute("metrics", service.metrics());
        model.addAttribute("tools", service.tools());
        model.addAttribute("workspaces", FileWorkspace.values());
        model.addAttribute("status", service.status());
        if (selected != null) {
            model.addAttribute("executions", service.executions(selected.id()));
            model.addAttribute("trace", service.toolTrace(selected.id()));
            List<FileChangeProposal> proposals = service.proposals(selected.id());
            model.addAttribute("proposals", proposals);
            if (!proposals.isEmpty()) {
                FileChangeProposal latest = proposals.getFirst();
                model.addAttribute("latestProposal", latest);
                model.addAttribute("changes", service.changes(latest.id()));
            } else {
                model.addAttribute("changes", List.<ProposedFileChange>of());
            }
        } else {
            model.addAttribute("proposals", List.of());
            model.addAttribute("changes", List.of());
        }
        HttpSession session = request.getSession(true);
        model.addAttribute("csrfToken", access.csrfToken(session));
        model.addAttribute("adminTokenConfigured", access.tokenConfigured());
        return "file-agent";
    }

    @PostMapping("/file-agent/tasks")
    public String create(@RequestParam(name = "goal") String goal,
            @RequestParam(name = "workspace", defaultValue = "PROJECT_WORKSPACE") String workspace,
            @RequestParam(name = "lectureId", required = false) String lectureId,
            RedirectAttributes redirect) {
        try {
            FileAgentTask task = service.createAndStart(FileWorkspace.from(workspace), lectureId, goal);
            redirect.addFlashAttribute("message", "Задача создана, агент выполняет поиск");
            return "redirect:/file-agent?taskId=" + task.id();
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/file-agent";
        }
    }

    @PostMapping("/file-agent/tasks/{id}/cancel")
    public String cancel(@PathVariable String id, RedirectAttributes redirect) {
        try {
            service.cancel(id);
            redirect.addFlashAttribute("message", "Запрошена отмена задачи");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/file-agent?taskId=" + id;
    }

    @PostMapping("/file-agent/tasks/{id}/regenerate")
    public String regenerate(@PathVariable String id, RedirectAttributes redirect) {
        try {
            FileAgentTask next = service.regenerate(id, "Перегенерировано пользователем");
            redirect.addFlashAttribute("message", "Создана новая задача с тем же goal");
            return "redirect:/file-agent?taskId=" + next.id();
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/file-agent?taskId=" + id;
        }
    }

    @PostMapping("/file-agent/proposals/{id}/approve")
    public String approve(@PathVariable long id,
            @RequestParam(name = "taskId", required = false) String taskId,
            RedirectAttributes redirect) {
        try {
            var result = service.approveAndApply(id, "admin-ui");
            redirect.addFlashAttribute(result.applied() ? "message" : "error", result.message());
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/file-agent" + (taskId == null || taskId.isBlank() ? "" : "?taskId=" + taskId);
    }

    @PostMapping("/file-agent/proposals/{id}/reject")
    public String reject(@PathVariable long id,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "reason", required = false) String reason,
            RedirectAttributes redirect) {
        try {
            service.reject(id, reason);
            redirect.addFlashAttribute("message", "Предложение отклонено, файлы не изменены");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/file-agent" + (taskId == null || taskId.isBlank() ? "" : "?taskId=" + taskId);
    }
}
