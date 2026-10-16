package com.lectureprocessor.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.ObjectMapper;

/**
 * Ticket persistence, lifecycle, history, isolation and concurrent writes against
 * a real SQLite schema.
 */
class SupportTicketServiceTest {

    private Path tempDir;
    private HikariDataSource dataSource;
    private SupportTicketRepository tickets;
    private SupportMessageRepository messages;
    private SupportToolCallRepository toolCalls;
    private SupportAccessService access;
    private SupportTicketService service;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("support-ticket-test");
        dataSource = hikari(tempDir.resolve("test.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
        }
        tickets = new SupportTicketRepository(jdbc);
        messages = new SupportMessageRepository(jdbc, new ObjectMapper());
        toolCalls = new SupportToolCallRepository(jdbc);
        access = new SupportAccessService(SupportProperties.defaults("test-install"));
        service = new SupportTicketService(tickets, messages, toolCalls, access,
                SupportProperties.defaults("test-install"));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static HikariDataSource hikari(Path path) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + path.toAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        return new HikariDataSource(config);
    }

    private static SupportPrincipal principal(String owner) {
        return new SupportPrincipal("test-install", owner);
    }

    private static SupportMessage userMessage(long ticketId, String content) {
        return new SupportMessage(null, ticketId, SupportMessageRole.USER, content, Instant.now(),
                null, null, List.of(), List.of(), null, null, null, null, null, null, null, null,
                null, null, null);
    }

    @Test
    void createGetAndAppendHistory() {
        SupportTicket ticket = service.create("Не работает Whisper", "TRANSCRIPTION", "L1",
                principal("s1"));
        assertThat(ticket.id()).isNotNull();
        assertThat(ticket.status()).isEqualTo(SupportTicketStatus.OPEN);
        assertThat(ticket.lectureId()).isEqualTo("L1");

        service.saveMessage(ticket.id(), userMessage(ticket.id(), "подробности"), principal("s1"));
        service.saveMessage(ticket.id(), new SupportMessage(null, ticket.id(),
                SupportMessageRole.ASSISTANT, "ответ", Instant.now(), "L1", null,
                List.of(SupportCitation.doc("README.md#1", "Запуск", "excerpt", 0.9)),
                List.of(new SupportToolTrace("get_job_status", true, 12, "ok")),
                "ollama", "qwen3:1.7b", true, 100L, 10, 20, null, null, 1, 0, null),
                principal("s1"));

        List<SupportMessage> history = service.history(ticket.id(), principal("s1"));
        assertThat(history).hasSize(2);
        assertThat(history.get(1).provider()).isEqualTo("ollama");
        assertThat(history.get(1).citations()).hasSize(1);
        assertThat(history.get(1).tools()).hasSize(1);
    }

    @Test
    void closeAndResolveTransitions() {
        SupportTicket ticket = service.create("q", "GENERAL", null, principal("s1"));
        SupportTicket resolved = service.resolve(ticket.id(), principal("s1"));
        assertThat(resolved.status()).isEqualTo(SupportTicketStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isNotNull();
        SupportTicket closed = service.close(ticket.id(), principal("s1"));
        assertThat(closed.status()).isEqualTo(SupportTicketStatus.CLOSED);
    }

    @Test
    void invalidTicketIsRejected() {
        assertThatThrownBy(() -> service.get(9999, principal("s1")))
                .isInstanceOf(SupportRequestException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    void scopeIsolationBlocksOtherInstallation() {
        SupportTicket ticket = service.create("private", "GENERAL", null,
                new SupportPrincipal("other-install", "s1"));
        assertThatThrownBy(() -> service.get(ticket.id(), principal("s1")))
                .isInstanceOf(SupportAccessDeniedException.class);
    }

    @Test
    void sessionIsolationBlocksOtherOwnerWhenConfigured() {
        SupportProperties sessionProps = new SupportProperties(true, "test-install", "session",
                "support-assistant-v1", List.of(), 8, 4000, 4000, 10, 16000, 2, 6, 0.15,
                java.time.Duration.ofMinutes(1));
        SupportAccessService sessionAccess = new SupportAccessService(sessionProps);
        SupportTicketService sessionService = new SupportTicketService(tickets, messages, toolCalls,
                sessionAccess, sessionProps);
        SupportTicket ticket = sessionService.create("q", "GENERAL", null, principal("owner-a"));
        assertThatThrownBy(() -> sessionService.get(ticket.id(), principal("owner-b")))
                .isInstanceOf(SupportAccessDeniedException.class);
        assertThat(sessionService.get(ticket.id(), principal("owner-a")).subject()).isEqualTo("q");
    }

    @Test
    void getOrCreateForLectureReusesOpenTicket() {
        SupportTicket first = service.getOrCreateForLecture("L42", principal("s1"));
        SupportTicket second = service.getOrCreateForLecture("L42", principal("s1"));
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void storesToolTraceAndRatings() {
        SupportTicket ticket = service.create("q", "GENERAL", null, principal("s1"));
        SupportMessage assistant = service.saveMessage(ticket.id(), new SupportMessage(null,
                ticket.id(), SupportMessageRole.ASSISTANT, "answer", Instant.now(), null, null,
                List.of(), List.of(new SupportToolTrace("get_job_status", true, 5, "ok")),
                "ollama", "m", true, 10L, 1, 2, null, null, 1, 0, null), principal("s1"));
        service.recordToolCalls(List.of(new SupportToolCall(null, assistant.id(), ticket.id(),
                "get_job_status", true, 5L, "ok", Instant.now())));
        assertThat(service.toolTrace(ticket.id(), principal("s1"))).hasSize(1);

        SupportMessage rated = service.rate(assistant.id(), 1, principal("s1"));
        assertThat(rated.feedback()).isEqualTo(1);
    }

    @Test
    void concurrentMessageWritesArePersisted() throws Exception {
        SupportTicket ticket = service.create("concurrent", "GENERAL", null, principal("s1"));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int index = i;
                tasks.add(() -> {
                    service.saveMessage(ticket.id(), userMessage(ticket.id(), "msg-" + index),
                            principal("s1"));
                    return null;
                });
            }
            List<Future<Void>> results = pool.invokeAll(tasks);
            for (Future<Void> result : results) {
                result.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(messages.countByTicket(ticket.id())).isEqualTo(20);
    }

    @Test
    void searchFindsBySubjectAndMessage() {
        SupportTicket ticket = service.create("Проблема с DeepSeek", "LLM", null, principal("s1"));
        service.saveMessage(ticket.id(), userMessage(ticket.id(), "ключ не проходит"), principal("s1"));
        assertThat(service.search(principal("s1"), "DeepSeek", null, 10)).hasSize(1);
        assertThat(service.search(principal("s1"), "не проходит", null, 10)).hasSize(1);
        assertThat(service.search(principal("s1"), "несвязанное", null, 10)).isEmpty();
    }
}
