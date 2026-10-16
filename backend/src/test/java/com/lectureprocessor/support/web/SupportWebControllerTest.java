package com.lectureprocessor.support.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
import com.lectureprocessor.support.SupportMessage;
import com.lectureprocessor.support.SupportMessageRole;
import com.lectureprocessor.support.SupportMetrics;
import com.lectureprocessor.support.SupportMetricsService;
import com.lectureprocessor.support.SupportPrincipal;
import com.lectureprocessor.support.SupportTicket;
import com.lectureprocessor.support.SupportTicketService;
import com.lectureprocessor.support.SupportTicketStatus;

import jakarta.servlet.http.HttpServletRequest;

@WebMvcTest(controllers = SupportWebController.class)
class SupportWebControllerTest {

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

    private final SupportPrincipal principal = new SupportPrincipal("test-install", "s1");

    @BeforeEach
    void setUp() {
        doNothing().when(access).requireCsrf(any(HttpServletRequest.class));
        when(access.resolve(any(HttpServletRequest.class))).thenReturn(principal);
        when(access.csrfToken(any())).thenReturn("csrf-token");
        when(assistant.tools()).thenReturn(List.of());
        when(metrics.metrics(any())).thenReturn(new SupportMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Map.of()));
        when(tickets.list(any(), any(), any(), anyInt())).thenReturn(List.of());
    }

    private SupportTicket ticket(long id) {
        return new SupportTicket(id, "test-install", "s1", "L1", "Тема", SupportCategory.LLM,
                SupportTicketStatus.OPEN, Instant.now(), Instant.now(), null);
    }

    @Test
    void supportPageRenders() throws Exception {
        mockMvc.perform(get("/support"))
                .andExpect(status().isOk())
                .andExpect(view().name("support"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Поддержка Lecture Processor")));
    }

    @Test
    void messageContentIsEscapedNotRenderedAsHtml() throws Exception {
        when(tickets.list(any(), any(), any(), anyInt())).thenReturn(List.of(ticket(1L)));
        when(tickets.get(eq(1L), any())).thenReturn(ticket(1L));
        when(tickets.history(eq(1L), any())).thenReturn(List.of(new SupportMessage(10L, 1L,
                SupportMessageRole.ASSISTANT, "<script>alert('xss')</script>", Instant.now(), null, null,
                List.of(), List.of(), "ollama", "m", true, 1L, 1, 2, null, null, 1, 0, null)));
        when(tickets.toolTrace(eq(1L), any())).thenReturn(List.of());

        mockMvc.perform(get("/support/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<script>alert('xss')</script>"))));
    }

    @Test
    void askReturnsAnswerFragment() throws Exception {
        SupportAnswer answer = new SupportAnswer("Готовый ответ", List.of(),
                List.of(), List.of(), 1L, 2L, "L1", null, "ollama", "m", true, List.of(), null, 5L,
                1, 2, null, null, false);
        when(assistant.ask(any(SupportAskRequest.class), any())).thenReturn(answer);
        when(tickets.get(eq(1L), any())).thenReturn(ticket(1L));
        when(tickets.history(eq(1L), any())).thenReturn(List.of());
        when(tickets.toolTrace(eq(1L), any())).thenReturn(List.of());

        mockMvc.perform(post("/support/ask")
                        .param("question", "Почему?")
                        .param("lectureId", "L1")
                        .param("_csrf", "csrf-token"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/support-answer"));
    }

    @Test
    void askWithoutCsrfShowsErrorFragment() throws Exception {
        doThrow(new SupportAccessDeniedException("Недействительный CSRF-токен"))
                .when(access).requireCsrf(any(HttpServletRequest.class));

        mockMvc.perform(post("/support/ask").param("question", "Почему?"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/support-answer"))
                .andExpect(model().attribute("error", "Недействительный CSRF-токен"));
    }

    @Test
    void lectureShortcutRedirectsToTicket() throws Exception {
        when(tickets.getOrCreateForLecture(eq("L1"), any())).thenReturn(ticket(3L));
        mockMvc.perform(get("/support/lecture/L1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/support/tickets/3"));
    }

    @Test
    void createTicketRedirectsToNewTicket() throws Exception {
        when(tickets.create(any(), any(), any(), any())).thenReturn(ticket(9L));
        mockMvc.perform(post("/support/tickets")
                        .param("subject", "Тема")
                        .param("category", "GENERAL")
                        .param("_csrf", "csrf-token"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/support/tickets/9"));
    }
}
