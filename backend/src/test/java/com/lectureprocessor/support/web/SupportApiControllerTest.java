package com.lectureprocessor.support.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lectureprocessor.support.SupportAccessDeniedException;
import com.lectureprocessor.support.SupportAccessService;
import com.lectureprocessor.support.SupportAnswer;
import com.lectureprocessor.support.SupportAskRequest;
import com.lectureprocessor.support.SupportAssistantService;
import com.lectureprocessor.support.SupportCategory;
import com.lectureprocessor.support.SupportCitation;
import com.lectureprocessor.support.SupportMessage;
import com.lectureprocessor.support.SupportMetrics;
import com.lectureprocessor.support.SupportMetricsService;
import com.lectureprocessor.support.SupportPrincipal;
import com.lectureprocessor.support.SupportProperties;
import com.lectureprocessor.support.SupportRequestException;
import com.lectureprocessor.support.SupportTicket;
import com.lectureprocessor.support.SupportTicketService;
import com.lectureprocessor.support.SupportTicketStatus;
import com.lectureprocessor.support.SupportToolTrace;

import jakarta.servlet.http.HttpServletRequest;

@WebMvcTest(controllers = SupportApiController.class)
class SupportApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupportAssistantService assistant;
    @MockitoBean
    private SupportTicketService tickets;
    @MockitoBean
    private SupportMetricsService metrics;
    @MockitoBean
    private SupportAccessService access;
    @MockitoBean
    private SupportProperties supportProperties;

    private final SupportPrincipal principal = new SupportPrincipal("test-install", "s1");

    @BeforeEach
    void allowCsrfAndResolve() {
        doNothing().when(access).requireCsrf(any(HttpServletRequest.class));
        when(access.resolve(any(HttpServletRequest.class))).thenReturn(principal);
    }

    private SupportAnswer answer() {
        return new SupportAnswer("Ответ", List.of("действие"),
                List.of(SupportCitation.doc("README.md#1", "Запуск", "excerpt", 0.9)),
                List.of(new SupportToolTrace("get_job_status", true, 5, "ok")), 1L, 2L, null, null,
                "ollama", "qwen3:1.7b", true, List.of(), null, 10L, 11, 22, null, null, false);
    }

    @Test
    void askReturnsGroundedAnswer() throws Exception {
        when(assistant.ask(any(SupportAskRequest.class), any())).thenReturn(answer());

        mockMvc.perform(post("/api/support/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"Как подключить DeepSeek?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Ответ"))
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.toolsUsed[0].name").value("get_job_status"))
                .andExpect(jsonPath("$.citations[0].source").value("README.md#1"));
    }

    @Test
    void missingCsrfIsForbidden() throws Exception {
        doThrow(new SupportAccessDeniedException("Недействительный CSRF-токен"))
                .when(access).requireCsrf(any(HttpServletRequest.class));

        mockMvc.perform(post("/api/support/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"q\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidQuestionIsBadRequest() throws Exception {
        when(assistant.ask(any(SupportAskRequest.class), any()))
                .thenThrow(new SupportRequestException("Вопрос не может быть пустым"));

        mockMvc.perform(post("/api/support/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Вопрос не может быть пустым"));
    }

    @Test
    void listReturnsTickets() throws Exception {
        when(tickets.list(eq(principal), any(), any(), anyInt()))
                .thenReturn(List.of(new SupportTicket(1L, "test-install", "s1", "L1", "Тема",
                        SupportCategory.LLM, SupportTicketStatus.OPEN, Instant.now(), Instant.now(), null)));

        mockMvc.perform(get("/api/support/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subject").value("Тема"));
    }

    @Test
    void metricsEndpointExposesAggregates() throws Exception {
        when(metrics.metrics(any())).thenReturn(new SupportMetrics(3, 1, 2, 5, 4, 10, 1, 120.5,
                2, 0, 100, 200, Map.of("ollama", 5)));

        mockMvc.perform(get("/api/support/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTickets").value(3))
                .andExpect(jsonPath("$.toolFailures").value(1));
    }

    @Test
    void lectureTicketShortcutWorks() throws Exception {
        when(tickets.getOrCreateForLecture(eq("L9"), any())).thenReturn(new SupportTicket(7L,
                "test-install", "s1", "L9", "Помощь", SupportCategory.GENERAL, SupportTicketStatus.OPEN,
                Instant.now(), Instant.now(), null));

        mockMvc.perform(get("/api/support/lecture/L9/ticket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.lectureId").value("L9"));
    }

    @Test
    void messagesRequireOwnership() throws Exception {
        when(tickets.history(eq(99L), any()))
                .thenThrow(new SupportAccessDeniedException("Тикет вне доступного пространства"));

        mockMvc.perform(get("/api/support/tickets/99/messages"))
                .andExpect(status().isForbidden());
    }

    @Test
    void feedbackStoresRating() throws Exception {
        SupportMessage message = new SupportMessage(5L, 1L, com.lectureprocessor.support.SupportMessageRole.ASSISTANT,
                "answer", Instant.now(), null, null, List.of(), List.of(), "ollama", "m", true,
                1L, 1, 2, null, null, 1, 0, 1);
        when(tickets.rate(eq(5L), eq(1), any())).thenReturn(message);

        mockMvc.perform(post("/api/support/messages/5/feedback")
                        .contentType("application/json")
                        .content("{\"rating\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedback").value(1));
    }
}
