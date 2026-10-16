package com.lectureprocessor.support.web;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.lectureprocessor.support.SupportAccessService;
import com.lectureprocessor.support.SupportAssistantService;
import com.lectureprocessor.support.SupportCategory;
import com.lectureprocessor.support.SupportMessage;
import com.lectureprocessor.support.SupportMessageRole;
import com.lectureprocessor.support.SupportMetricsService;
import com.lectureprocessor.support.SupportPrincipal;
import com.lectureprocessor.support.SupportTicket;
import com.lectureprocessor.support.SupportTicketService;
import com.lectureprocessor.support.SupportTicketStatus;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Thymeleaf/HTMX UI for the support assistant.
 *
 * <ul>
 *   <li>{@code GET /support} — ticket list, creation form and chat;</li>
 *   <li>{@code POST /support/ask} — HTMX answer fragment (CSRF protected);</li>
 *   <li>{@code GET /support/lecture/{id}} — lecture-page shortcut that reuses or
 *       creates a ticket and never asks the user for a job id.</li>
 * </ul>
 */
@Controller
public class SupportWebController {

    private final SupportAssistantService assistant;
    private final SupportTicketService tickets;
    private final SupportMetricsService metrics;
    private final SupportAccessService access;

    public SupportWebController(SupportAssistantService assistant, SupportTicketService tickets,
            SupportMetricsService metrics, SupportAccessService access) {
        this.assistant = assistant;
        this.tickets = tickets;
        this.metrics = metrics;
        this.access = access;
    }

    @GetMapping("/support")
    public String page(@RequestParam(name = "ticketId", required = false) Long ticketId,
            @RequestParam(name = "lectureId", required = false) String lectureId,
            HttpServletRequest request, Model model) {
        SupportPrincipal principal = access.resolve(request);
        populate(model, principal, ticketId, request);
        model.addAttribute("lectureId", lectureId);
        return "support";
    }

    @GetMapping("/support/tickets/{id}")
    public String ticket(@PathVariable long id, HttpServletRequest request, Model model) {
        SupportPrincipal principal = access.resolve(request);
        populate(model, principal, id, request);
        return "support";
    }

    @PostMapping("/support/tickets")
    public String createTicket(@RequestParam(name = "subject", required = false) String subject,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "lectureId", required = false) String lectureId,
            HttpServletRequest request, RedirectAttributes redirect) {
        try {
            access.requireCsrf(request);
            SupportTicket ticket = tickets.create(subject, category, lectureId, access.resolve(request));
            return "redirect:/support/tickets/" + ticket.id();
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/support";
        }
    }

    /** Lecture-page shortcut: open or continue the ticket linked to a lecture. */
    @GetMapping("/support/lecture/{lectureId}")
    public String lecture(@PathVariable String lectureId, HttpServletRequest request) {
        SupportTicket ticket = tickets.getOrCreateForLecture(lectureId, access.resolve(request));
        return "redirect:/support/tickets/" + ticket.id();
    }

    @PostMapping("/support/ask")
    public String ask(@RequestParam(name = "question") String question,
            @RequestParam(name = "ticketId", required = false) Long ticketId,
            @RequestParam(name = "lectureId", required = false) String lectureId,
            HttpServletRequest request, Model model) {
        model.addAttribute("question", question);
        SupportPrincipal principal = access.resolve(request);
        SupportTicket selected = null;
        try {
            access.requireCsrf(request);
            var answer = assistant.ask(new com.lectureprocessor.support.SupportAskRequest(
                    question, ticketId, lectureId, null), principal);
            model.addAttribute("answer", answer);
            if (answer.ticketId() != null) {
                selected = tickets.get(answer.ticketId(), principal);
            }
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
        }
        model.addAttribute("selected", selected);
        model.addAttribute("messages", selected == null ? List.of()
                : tickets.history(selected.id(), principal));
        model.addAttribute("trace", selected == null ? List.of()
                : tickets.toolTrace(selected.id(), principal));
        model.addAttribute("csrfToken", access.csrfToken(request.getSession(true)));
        return "fragments/support-answer";
    }

    @PostMapping("/support/tickets/{id}/close")
    public String close(@PathVariable long id, HttpServletRequest request,
            RedirectAttributes redirect) {
        try {
            access.requireCsrf(request);
            tickets.close(id, access.resolve(request));
            redirect.addFlashAttribute("message", "Обращение закрыто");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/support/tickets/" + id;
    }

    @PostMapping("/support/tickets/{id}/resolve")
    public String resolve(@PathVariable long id, HttpServletRequest request,
            RedirectAttributes redirect) {
        try {
            access.requireCsrf(request);
            tickets.resolve(id, access.resolve(request));
            redirect.addFlashAttribute("message", "Обращение помечено решённым");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/support/tickets/" + id;
    }

    @PostMapping("/support/messages/{id}/feedback")
    public String feedback(@PathVariable long id,
            @RequestParam(name = "helpful") int helpful,
            @RequestParam(name = "ticketId") long ticketId, HttpServletRequest request,
            RedirectAttributes redirect) {
        try {
            access.requireCsrf(request);
            tickets.rate(id, helpful, access.resolve(request));
            redirect.addFlashAttribute("message", helpful > 0 ? "Спасибо за оценку" : "Учтём, попробуем помочь точнее");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/support/tickets/" + ticketId;
    }

    private void populate(Model model, SupportPrincipal principal, Long ticketId, HttpServletRequest request) {
        model.addAttribute("tickets", tickets.list(principal, null, null, 100));
        model.addAttribute("categories", SupportCategory.values());
        model.addAttribute("statuses", SupportTicketStatus.values());
        model.addAttribute("tools", assistant.tools());
        model.addAttribute("metrics", metrics.metrics(principal));
        HttpSession session = request.getSession(true);
        model.addAttribute("csrfToken", access.csrfToken(session));
        SupportTicket selected = null;
        if (ticketId != null) {
            try {
                selected = tickets.get(ticketId, principal);
            } catch (RuntimeException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        model.addAttribute("selected", selected);
        if (selected != null) {
            model.addAttribute("messages", tickets.history(selected.id(), principal));
            model.addAttribute("trace", tickets.toolTrace(selected.id(), principal));
        } else {
            model.addAttribute("messages", List.<SupportMessage>of());
            model.addAttribute("trace", List.of());
        }
        model.addAttribute("assistantRole", SupportMessageRole.ASSISTANT);
    }
}
