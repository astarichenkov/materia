package com.lectureprocessor.support.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lectureprocessor.mcp.McpArguments;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;
import com.lectureprocessor.support.SupportAccessDeniedException;
import com.lectureprocessor.support.SupportAccessService;
import com.lectureprocessor.support.SupportPrincipal;
import com.lectureprocessor.support.SupportScopeGuard;
import com.lectureprocessor.support.SupportTicket;
import com.lectureprocessor.support.SupportTicketRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * MCP tool: read a support ticket (metadata + lecture linkage) within the
 * authorized scope. Never returns a ticket outside the caller's scope.
 */
@Component
public class GetTicketTool implements McpTool {

    private final SupportTicketRepository tickets;
    private final SupportAccessService access;
    private final SupportScopeGuard guard;
    private final ObjectMapper objectMapper;

    public GetTicketTool(SupportTicketRepository tickets, SupportAccessService access,
            SupportScopeGuard guard, ObjectMapper objectMapper) {
        this.tickets = tickets;
        this.access = access;
        this.guard = guard;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "get_ticket";
    }

    @Override
    public String description() {
        return "Возвращает метаданные обращения поддержки (статус, категория, привязка к лекции) "
                + "в пределах доступного scope. Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "ticket_id", McpSchemas.string("ID обращения")), List.of("ticket_id"));
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        Long ticketId = parseId(McpArguments.string(arguments, "ticket_id", null));
        if (ticketId == null) {
            return McpToolResult.error("Параметр ticket_id обязателен и должен быть числом");
        }
        SupportPrincipal principal = guard.current();
        SupportTicket ticket = tickets.findById(ticketId).orElse(null);
        if (ticket == null) {
            return McpToolResult.error("Обращение не найдено: " + ticketId);
        }
        try {
            access.assertCanAccess(ticket, principal);
        } catch (SupportAccessDeniedException e) {
            return McpToolResult.error("Доступ к обращению запрещён");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ticketId", ticket.id());
        view.put("subject", ticket.subject());
        view.put("category", ticket.category().name());
        view.put("status", ticket.status().name());
        view.put("lectureId", ticket.lectureId());
        view.put("createdAt", ticket.createdAt().toString());
        view.put("updatedAt", ticket.updatedAt().toString());
        view.put("resolvedAt", ticket.resolvedAt() == null ? null : ticket.resolvedAt().toString());
        return McpToolResult.ok(objectMapper.writeValueAsString(view));
    }

    static Long parseId(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
