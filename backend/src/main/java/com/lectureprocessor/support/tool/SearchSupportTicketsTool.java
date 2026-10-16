package com.lectureprocessor.support.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lectureprocessor.mcp.McpArguments;
import com.lectureprocessor.mcp.McpSchemas;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolResult;
import com.lectureprocessor.support.SupportScopeGuard;
import com.lectureprocessor.support.SupportTicket;
import com.lectureprocessor.support.SupportTicketRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * MCP tool: keyword search across support tickets within the authorized scope.
 * Only the caller's installation scope is searched — never another scope.
 */
@Component
public class SearchSupportTicketsTool implements McpTool {

    private final SupportTicketRepository tickets;
    private final SupportScopeGuard guard;
    private final ObjectMapper objectMapper;

    public SearchSupportTicketsTool(SupportTicketRepository tickets, SupportScopeGuard guard,
            ObjectMapper objectMapper) {
        this.tickets = tickets;
        this.guard = guard;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "search_support_tickets";
    }

    @Override
    public String description() {
        return "Ищет обращения поддержки по подстроке в теме и сообщениях в пределах доступного scope. "
                + "Параметры: query, category (опционально), limit (1..25). Read-only.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return McpSchemas.object(Map.of(
                "query", McpSchemas.string("Поисковый запрос"),
                "category", McpSchemas.string("Категория (GENERAL, DOWNLOAD, ...)"),
                "limit", McpSchemas.integer("Максимум обращений", 1, 25)), List.of("query"));
    }

    @Override
    public McpToolResult invoke(Map<String, Object> arguments) {
        String query = McpArguments.string(arguments, "query", null);
        if (query == null) {
            return McpToolResult.error("Параметр query обязателен");
        }
        String category = McpArguments.string(arguments, "category", null);
        int limit = McpArguments.integer(arguments, "limit", 10, 1, 25);
        List<SupportTicket> found = tickets.search(guard.current().scope(), query, category, limit);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("count", found.size());
        view.put("tickets", found.stream().map(ticket -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ticketId", ticket.id());
            entry.put("subject", ticket.subject());
            entry.put("category", ticket.category().name());
            entry.put("status", ticket.status().name());
            entry.put("lectureId", ticket.lectureId());
            entry.put("updatedAt", ticket.updatedAt().toString());
            return entry;
        }).toList());
        return McpToolResult.ok(objectMapper.writeValueAsString(view));
    }
}
