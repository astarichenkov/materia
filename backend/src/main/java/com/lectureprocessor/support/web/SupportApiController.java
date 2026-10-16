package com.lectureprocessor.support.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lectureprocessor.support.SupportAccessService;
import com.lectureprocessor.support.SupportAnswer;
import com.lectureprocessor.support.SupportAskRequest;
import com.lectureprocessor.support.SupportAssistantService;
import com.lectureprocessor.support.SupportMessage;
import com.lectureprocessor.support.SupportMetrics;
import com.lectureprocessor.support.SupportMetricsService;
import com.lectureprocessor.support.SupportPrincipal;
import com.lectureprocessor.support.SupportTicket;
import com.lectureprocessor.support.SupportTicketService;
import com.lectureprocessor.support.SupportTicketStatus;
import com.lectureprocessor.support.SupportToolCall;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST API for the support assistant. State-changing requests require a CSRF
 * token from the browser session ({@code X-Support-Csrf} header or {@code _csrf}
 * form field); the ticket scope is resolved from the same session.
 */
@RestController
@RequestMapping("/api/support")
public class SupportApiController {

    private final SupportAssistantService assistant;
    private final SupportTicketService tickets;
    private final SupportMetricsService metrics;
    private final SupportAccessService access;

    public SupportApiController(SupportAssistantService assistant, SupportTicketService tickets,
            SupportMetricsService metrics, SupportAccessService access) {
        this.assistant = assistant;
        this.tickets = tickets;
        this.metrics = metrics;
        this.access = access;
    }

    @PostMapping("/ask")
    public SupportAnswer ask(@RequestBody SupportAskRequest request, HttpServletRequest http) {
        access.requireCsrf(http);
        return assistant.ask(request, access.resolve(http));
    }

    @GetMapping("/tickets")
    public List<SupportTicket> list(@RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "limit", defaultValue = "50") int limit, HttpServletRequest http) {
        SupportPrincipal principal = access.resolve(http);
        if (query != null && !query.isBlank()) {
            return tickets.search(principal, query, category, limit);
        }
        return tickets.list(principal, status, category, limit);
    }

    @PostMapping("/tickets")
    public SupportTicket create(@RequestBody CreateTicketRequest request, HttpServletRequest http) {
        access.requireCsrf(http);
        return tickets.create(request == null ? null : request.subject(),
                request == null ? null : request.category(),
                request == null ? null : request.lectureId(), access.resolve(http));
    }

    @GetMapping("/tickets/{id}")
    public SupportTicket get(@PathVariable long id, HttpServletRequest http) {
        return tickets.get(id, access.resolve(http));
    }

    @GetMapping("/tickets/{id}/messages")
    public List<SupportMessage> messages(@PathVariable long id, HttpServletRequest http) {
        return tickets.history(id, access.resolve(http));
    }

    @GetMapping("/tickets/{id}/trace")
    public List<SupportToolCall> trace(@PathVariable long id, HttpServletRequest http) {
        return tickets.toolTrace(id, access.resolve(http));
    }

    @PostMapping("/tickets/{id}/status")
    public SupportTicket status(@PathVariable long id, @RequestBody StatusRequest request,
            HttpServletRequest http) {
        access.requireCsrf(http);
        return tickets.updateStatus(id, SupportTicketStatus.from(request == null ? null : request.status()),
                access.resolve(http));
    }

    @PostMapping("/messages/{id}/feedback")
    public SupportMessage feedback(@PathVariable long id, @RequestBody FeedbackRequest request,
            HttpServletRequest http) {
        access.requireCsrf(http);
        Integer rating = request == null ? null : request.rating();
        return tickets.rate(id, rating, access.resolve(http));
    }

    @GetMapping("/lecture/{lectureId}/ticket")
    public SupportTicket lectureTicket(@PathVariable String lectureId, HttpServletRequest http) {
        return tickets.getOrCreateForLecture(lectureId, access.resolve(http));
    }

    @GetMapping("/metrics")
    public SupportMetrics metrics(HttpServletRequest http) {
        return metrics.metrics(access.resolve(http));
    }

    @GetMapping("/tools")
    public List<SupportAssistantService.ToolView> tools() {
        return assistant.tools();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", true);
        status.put("tools", assistant.tools().stream().map(SupportAssistantService.ToolView::name).toList());
        status.put("isolation", "install");
        return status;
    }

    /** Request body for ticket creation. */
    public record CreateTicketRequest(String subject, String category, String lectureId) {
    }

    /** Request body for a status transition. */
    public record StatusRequest(String status) {
    }

    /** Request body for a "помогло / не помогло" rating. */
    public record FeedbackRequest(Integer rating) {
    }
}
