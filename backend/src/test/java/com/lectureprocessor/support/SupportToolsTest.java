package com.lectureprocessor.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.lectureprocessor.mcp.McpToolResult;
import com.lectureprocessor.repository.DownloadStateRepository;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.PipelineJobRepository;
import com.lectureprocessor.repository.StageCheckpointRepository;
import com.lectureprocessor.repository.TimingRepository;
import com.lectureprocessor.support.tool.GetJobDiagnosticsTool;
import com.lectureprocessor.support.tool.GetTicketTool;
import com.lectureprocessor.support.tool.ListTicketMessagesTool;
import com.lectureprocessor.support.tool.SearchSupportTicketsTool;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.ObjectMapper;

/** Support MCP tools: happy paths, missing data, scope isolation, redaction, limits. */
class SupportToolsTest {

    private Path tempDir;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private SupportTicketService tickets;
    private SupportMessageRepository messages;
    private SupportAccessService access;
    private SupportScopeGuard guard;

    private GetTicketTool getTicket;
    private ListTicketMessagesTool listMessages;
    private SearchSupportTicketsTool searchTickets;
    private GetJobDiagnosticsTool diagnostics;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("support-tools-test");
        dataSource = hikari(tempDir.resolve("test.db"));
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V2__week7_ingestion.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V3__day31_developer_assistant.sql"));
        }
        objectMapper = new ObjectMapper();
        SupportProperties props = SupportProperties.defaults("test-install");
        SupportTicketRepository ticketRepo = new SupportTicketRepository(jdbc);
        messages = new SupportMessageRepository(jdbc, objectMapper);
        SupportToolCallRepository toolCalls = new SupportToolCallRepository(jdbc);
        access = new SupportAccessService(props);
        guard = new SupportScopeGuard(access);
        tickets = new SupportTicketService(ticketRepo, messages, toolCalls, access, props);

        getTicket = new GetTicketTool(ticketRepo, access, guard, objectMapper);
        listMessages = new ListTicketMessagesTool(ticketRepo, messages, access, guard, objectMapper);
        searchTickets = new SearchSupportTicketsTool(ticketRepo, guard, objectMapper);
        diagnostics = new GetJobDiagnosticsTool(new LectureRepository(jdbc), new PipelineJobRepository(jdbc),
                new StageCheckpointRepository(jdbc, objectMapper), new DownloadStateRepository(jdbc),
                new TimingRepository(jdbc), objectMapper);
    }

    @AfterEach
    void tearDown() {
        SupportAccessContext.clear();
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

    private static McpToolResult invoke(com.lectureprocessor.mcp.McpTool tool, Map<String, Object> args) {
        return tool.invoke(args);
    }

    @Test
    void getTicketReturnsScopedMetadata() {
        SupportAccessContext.set(new SupportPrincipal("test-install", "local"));
        SupportTicket ticket = tickets.create("Тема", "TRANSCRIPTION", "L1",
                new SupportPrincipal("test-install", "s1"));
        McpToolResult result = invoke(getTicket, Map.of("ticket_id", ticket.id()));
        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("\"lectureId\":\"L1\"").contains("TRANSCRIPTION");
    }

    @Test
    void getTicketMissingIsError() {
        SupportAccessContext.set(new SupportPrincipal("test-install", "local"));
        assertThat(invoke(getTicket, Map.of("ticket_id", 9999)).isError()).isTrue();
    }

    @Test
    void getTicketOutsideScopeIsDenied() {
        SupportTicket ticket = tickets.create("other", "GENERAL", null,
                new SupportPrincipal("other-install", "s1"));
        SupportAccessContext.set(new SupportPrincipal("test-install", "local"));
        McpToolResult result = invoke(getTicket, Map.of("ticket_id", ticket.id()));
        assertThat(result.isError()).isTrue();
        assertThat(result.text()).doesNotContain("other");
    }

    @Test
    void listTicketMessagesIsBoundedAndScoped() {
        SupportAccessContext.set(new SupportPrincipal("test-install", "local"));
        SupportTicket ticket = tickets.create("t", "GENERAL", null,
                new SupportPrincipal("test-install", "s1"));
        tickets.saveMessage(ticket.id(), new SupportMessage(null, ticket.id(),
                SupportMessageRole.USER, "x".repeat(5000), Instant.now(), null, null, List.of(),
                List.of(), null, null, null, null, null, null, null, null, null, null, null),
                new SupportPrincipal("test-install", "s1"));
        McpToolResult result = invoke(listMessages, Map.of("ticket_id", ticket.id(), "limit", 5));
        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("…");
        assertThat(result.text().length()).isLessThan(3000);
    }

    @Test
    void searchTicketsOnlyReturnsOwnScope() {
        tickets.create("visible", "GENERAL", null, new SupportPrincipal("test-install", "s1"));
        tickets.create("hidden", "GENERAL", null, new SupportPrincipal("other-install", "s1"));
        SupportAccessContext.set(new SupportPrincipal("test-install", "local"));
        McpToolResult result = invoke(searchTickets, Map.of("query", "i"));
        assertThat(result.text()).contains("visible").doesNotContain("hidden");
    }

    @Test
    void jobDiagnosticsReportsFailedStageAndRedactsSecrets() {
        insertLecture("L10", "FAILED", null);
        jdbc.update("""
                INSERT INTO pipeline_job (lecture_id, state, current_stage, attempts, error,
                        created_at, updated_at) VALUES (?,?,?,?,?,?,?)
                """, "L10", "FAILED", "TEXT_PROCESSING", 2, "boom api_key=supersecretvalue",
                Instant.now().toString(), Instant.now().toString());
        jdbc.update("""
                INSERT INTO stage_checkpoint (lecture_id, stage, state, attempt, started_at, finished_at,
                        error, artifact_manifest_json) VALUES (?,?,?,?,?,?,?,?)
                """, "L10", "TRANSCRIPTION", "COMPLETED", 1, Instant.now().toString(),
                Instant.now().toString(), null, null);
        jdbc.update("""
                INSERT INTO stage_checkpoint (lecture_id, stage, state, attempt, started_at, finished_at,
                        error, artifact_manifest_json) VALUES (?,?,?,?,?,?,?,?)
                """, "L10", "TEXT_PROCESSING", "FAILED", 2, Instant.now().toString(),
                Instant.now().toString(), "LLM unavailable api_key=supersecretvalue", null);

        SupportAccessContext.set(access.installPrincipal());
        McpToolResult result = invoke(diagnostics, Map.of("lecture_id", "L10"));
        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("\"available\":true").contains("TEXT_PROCESSING")
                .contains("TRANSCRIPTION");
        assertThat(result.text()).doesNotContain("supersecretvalue");
        assertThat(result.text()).contains("[REDACTED]");
    }

    @Test
    void jobDiagnosticsMissingTaskIsHonest() {
        SupportAccessContext.set(access.installPrincipal());
        McpToolResult result = invoke(diagnostics, Map.of("lecture_id", "does-not-exist"));
        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("\"available\":false");
    }

    private void insertLecture(String id, String status, String error) {
        jdbc.update("""
                INSERT INTO lecture (id, original_filename, stored_filename, size_bytes, status,
                        clean_transcript, extract_slides, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, id, id + ".mp4", "stored.mp4", 10L, status, 0, 0,
                Instant.now().toString(), Instant.now().toString());
    }
}
