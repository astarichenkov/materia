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
import com.lectureprocessor.support.SupportMessage;
import com.lectureprocessor.support.SupportMessageRepository;
import com.lectureprocessor.support.SupportScopeGuard;
import com.lectureprocessor.support.SupportTicket;
import com.lectureprocessor.support.SupportTicketRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * MCP tool: bounded conversation history of a ticket within the authorized
 * scope. Long messages are truncated so the payload stays bounded.
 */
@Component
public class ListTicketMessagesTool implements McpTool {

    private static final int MAX_CONTENT = 1200;

    private final SupportTicketRepository tickets;
    private final SupportMessageRepository messages;
    private final SupportAccessService access;
    private final SupportScopeGuard guard;
    private final ObjectMapper objectMapper;

    public ListTicketMessagesTool(SupportTicketRepository tickets, SupportMessageRepository messages,
            SupportAccessService access, SupportScopeGuard guard, ObjectMapper objectMapper) {
        this.tickets = tickets;
        this.messages = messages;
        this.access = access;
        this.guard = guard;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "list_ticket_messages";
    }

    @Override
    public String description() {
        return "Возвращает ограниченную историю сообщений обращения (role, content, время). "
                + "Параметры: ticket_id, limit (1..50). Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "ticket_id", McpSchemas.string("ID обращения"),
                "limit", McpSchemas.integer("Сколько последних сообщений", 1, 50)),
                List.of("ticket_id"));
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        Long ticketId = GetTicketTool.parseId(McpArguments.string(arguments, "ticket_id", null));
        if (ticketId == null) {
            return McpToolResult.error("Параметр ticket_id обязателен и должен быть числом");
        }
        int limit = McpArguments.integer(arguments, "limit", 10, 1, 50);
        SupportTicket ticket = tickets.findById(ticketId).orElse(null);
        if (ticket == null) {
            return McpToolResult.error("Обращение не найдено: " + ticketId);
        }
        try {
            access.assertCanAccess(ticket, guard.current());
        } catch (SupportAccessDeniedException e) {
            return McpToolResult.error("Доступ к обращению запрещён");
        }
        List<SupportMessage> history = messages.findRecentByTicket(ticketId, limit);
        if (history.isEmpty()) {
            return McpToolResult.ok("(сообщений нет)");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ticketId", ticketId);
        view.put("count", history.size());
        view.put("messages", history.stream().map(message -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", message.id());
            entry.put("role", message.role().name());
            entry.put("content", truncate(message.content()));
            entry.put("createdAt", message.createdAt().toString());
            if (message.lectureId() != null) {
                entry.put("lectureId", message.lectureId());
            }
            return entry;
        }).toList());
        return McpToolResult.ok(objectMapper.writeValueAsString(view));
    }

    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= MAX_CONTENT ? content : content.substring(0, MAX_CONTENT) + "…";
    }
}
