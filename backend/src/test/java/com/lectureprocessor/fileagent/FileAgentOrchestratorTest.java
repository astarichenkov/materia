package com.lectureprocessor.fileagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.lectureprocessor.assistant.DocumentationRetrievalService;
import com.lectureprocessor.assistant.ProjectRootResolver;
import com.lectureprocessor.fileagent.model.FileAgentTask;
import com.lectureprocessor.fileagent.model.FileChangeProposal;
import com.lectureprocessor.fileagent.repository.FileAgentExecutionRepository;
import com.lectureprocessor.fileagent.repository.FileAgentTaskRepository;
import com.lectureprocessor.fileagent.repository.FileAgentToolCallRepository;
import com.lectureprocessor.fileagent.repository.FileChangeProposalRepository;
import com.lectureprocessor.fileagent.repository.ProposedFileChangeRepository;
import com.lectureprocessor.fileagent.tool.GetFileMetadataTool;
import com.lectureprocessor.fileagent.tool.ReadProjectFileTool;
import com.lectureprocessor.fileagent.tool.SearchCodeSymbolsTool;
import com.lectureprocessor.fileagent.tool.SearchProjectDocumentationTool;
import com.lectureprocessor.fileagent.tool.SearchProjectFilesTool;
import com.lectureprocessor.fileagent.FileWorkspace;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceResolver;
import com.lectureprocessor.fileagent.workspace.WorkspaceFileService;
import com.lectureprocessor.fileagent.workspace.WorkspacePathGuard;
import com.lectureprocessor.llm.CloudCostService;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolRegistry;
import com.lectureprocessor.process.CancellationToken;
import com.lectureprocessor.repository.DocChunkRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.FixedTaskResolver;
import com.lectureprocessor.support.TestProperties;
import com.lectureprocessor.text.PromptTemplates;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.ObjectMapper;

/**
 * Integration test of the Day 34 pipeline with a real SQLite database, real MCP
 * file tools and a deterministic fake LLM. No network calls.
 */
class FileAgentOrchestratorTest {

    private static final String GOAL = "Найди все места, где используется LlmTaskSettingsService, "
            + "и обнови документацию о выборе моделей для разных задач.";

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private ObjectMapper objectMapper;
    private FileAgentProperties properties;
    private FakeLlmProvider llm;
    private ThreadPoolTaskExecutor executor;
    private FileAgentOrchestrator orchestrator;
    private FileAgentService service;
    private FileChangeApplicationService application;
    private FileChangeProposalRepository proposals;
    private Path projectRoot;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot.resolve("docs"));
        Files.createDirectories(projectRoot.resolve("src/main/java/com/example"));
        Files.writeString(projectRoot.resolve("README.md"), "# Project\n\nold readme\n");
        Files.writeString(projectRoot.resolve("docs/llm.md"), "# LLM\n\nold llm docs\n");
        Files.writeString(projectRoot.resolve("src/main/java/com/example/LlmTaskSettingsService.java"), """
                package com.example;

                public class LlmTaskSettingsService {
                    public String configFor(String task) {
                        return task;
                    }
                }
                """);
        Files.writeString(projectRoot.resolve("docs/architecture.md"),
                "# Architecture\n\nLecture processing pipeline.\n");

        dataSource = hikari(tempDir.resolve("file-agent.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V7__day34_file_agent.sql"));
        }
        objectMapper = new ObjectMapper();
        properties = FileAgentProperties.defaults(projectRoot.toString());

        FileAgentTaskRepository tasks = new FileAgentTaskRepository(jdbc, objectMapper);
        FileAgentExecutionRepository executions = new FileAgentExecutionRepository(jdbc);
        FileAgentToolCallRepository toolCalls = new FileAgentToolCallRepository(jdbc);
        proposals = new FileChangeProposalRepository(jdbc);
        ProposedFileChangeRepository changes = new ProposedFileChangeRepository(jdbc);

        var appProperties = TestProperties.defaults(tempDir.resolve("storage").toString());
        var storage = new FileStorageService(appProperties);
        var workspaceResolver = new FileWorkspaceResolver(properties,
                new ProjectRootResolver(appProperties), storage);
        var guard = new WorkspacePathGuard(properties);
        var files = new WorkspaceFileService(guard, properties);

        llm = new FakeLlmProvider();
        llm.setDetailed(true);
        llm.setTokenUsage(100, 200);
        llm.setChatFunction((system, user) -> user.contains("candidate files")
                ? planJson() : proposalJson());

        DocChunkRepository docRepo = mock(DocChunkRepository.class);
        when(docRepo.findAll()).thenReturn(List.of());
        var retrieval = new DocumentationRetrievalService(docRepo, llm,
                TestProperties.defaults(tempDir.resolve("storage").toString()));

        List<McpTool> tools = List.of(
                new SearchProjectFilesTool(workspaceResolver, files, properties),
                new SearchCodeSymbolsTool(workspaceResolver, files, properties),
                new ReadProjectFileTool(workspaceResolver, files, properties),
                new GetFileMetadataTool(workspaceResolver, files, properties),
                new SearchProjectDocumentationTool(retrieval, properties));
        McpToolRegistry registry = new McpToolRegistry(tools);
        FileAgentToolExecutor toolExecutor = new FileAgentToolExecutor(registry, properties, toolCalls);

        FileAgentPlanner planner = new FileAgentPlanner(new FixedTaskResolver(llm),
                new PromptTemplates(), files, properties, objectMapper);
        FileAgentContextBuilder contextBuilder = new FileAgentContextBuilder(properties);
        FileAgentProposalParser parser = new FileAgentProposalParser(objectMapper);
        FileAgentPatchValidator patchValidator = new FileAgentPatchValidator(guard, files, properties);
        FileAgentResultValidator resultValidator = new FileAgentResultValidator();
        FileAgentVerificationService verification = new FileAgentVerificationService(files, guard);
        application = new FileChangeApplicationService(proposals, changes, tasks, workspaceResolver,
                guard, files, verification, storage);

        orchestrator = new FileAgentOrchestrator(properties, tasks, executions, toolExecutor,
                workspaceResolver, files, planner, contextBuilder, parser, patchValidator,
                resultValidator, proposals, changes, new FixedTaskResolver(llm), new PromptTemplates(),
                new CloudCostService(appProperties));

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();
        service = new FileAgentService(properties, orchestrator, workspaceResolver, tasks, executions,
                toolCalls, proposals, changes, application, toolExecutor,
                new FileAgentMetricsService(tasks), executor);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void producesProposalRequiresApprovalAndThenWritesFiles() throws Exception {
        FileAgentTask task = service.create(FileWorkspace.PROJECT, null, GOAL);
        orchestrator.run(task.id(), new CancellationToken());

        FileAgentTask awaiting = service.get(task.id());
        assertThat(awaiting.status()).isEqualTo(FileAgentTaskStatus.AWAITING_APPROVAL);
        assertThat(awaiting.filesRead()).isGreaterThanOrEqualTo(3);
        assertThat(awaiting.toolCalls()).isGreaterThanOrEqualTo(5);

        List<FileChangeProposal> proposalList = service.proposals(task.id());
        assertThat(proposalList).hasSize(1);
        FileChangeProposal proposal = proposalList.getFirst();
        assertThat(proposal.status()).isEqualTo(ProposalStatus.AWAITING_APPROVAL);
        assertThat(proposal.diff()).contains("--- a/README.md", "+new readme",
                "--- a/docs/llm.md", "+new routing docs");
        assertThat(service.toolTrace(task.id()))
                .extracting(call -> call.toolName())
                .contains("search_code_symbols", "read_project_file");

        String before = Files.readString(projectRoot.resolve("README.md"));
        assertThat(before).contains("old readme");

        var result = service.approveAndApply(proposal.id(), "test-admin");
        assertThat(result.applied()).isTrue();
        assertThat(result.status()).isEqualTo(ProposalStatus.COMPLETED);
        assertThat(Files.readString(projectRoot.resolve("README.md"))).contains("new readme");
        assertThat(Files.readString(projectRoot.resolve("docs/llm.md"))).contains("new routing docs");
        assertThat(service.get(task.id()).status()).isEqualTo(FileAgentTaskStatus.COMPLETED);
        assertThat(service.get(task.id()).verificationStatus()).startsWith("PASSED");

        assertThatThrownBy(() -> service.approveAndApply(proposal.id(), "test-admin"))
                .isInstanceOf(FileAgentException.class);
    }

    @Test
    void detectsConflictAndDoesNotOverwriteChangedFile() throws Exception {
        FileAgentTask task = service.create(FileWorkspace.PROJECT, null, GOAL);
        orchestrator.run(task.id(), new CancellationToken());
        FileChangeProposal proposal = service.proposals(task.id()).getFirst();

        Files.writeString(projectRoot.resolve("README.md"), "# Project\nexternal change\n");
        var result = service.approveAndApply(proposal.id(), "test-admin");

        assertThat(result.applied()).isFalse();
        assertThat(result.status()).isEqualTo(ProposalStatus.CONFLICT);
        assertThat(Files.readString(projectRoot.resolve("README.md"))).contains("external change");
        assertThat(service.get(task.id()).status()).isEqualTo(FileAgentTaskStatus.CONFLICT);
    }

    @Test
    void rejectionLeavesFilesUntouched() throws Exception {
        FileAgentTask task = service.create(FileWorkspace.PROJECT, null, GOAL);
        orchestrator.run(task.id(), new CancellationToken());
        FileChangeProposal proposal = service.proposals(task.id()).getFirst();

        service.reject(proposal.id(), "not needed");

        assertThat(service.proposal(proposal.id()).status()).isEqualTo(ProposalStatus.REJECTED);
        assertThat(Files.readString(projectRoot.resolve("README.md"))).contains("old readme");
        assertThat(service.get(task.id()).status()).isEqualTo(FileAgentTaskStatus.REJECTED);
    }

    @Test
    void verificationFailureRollsBackWrittenFiles() throws Exception {
        FileAgentTask task = service.create(FileWorkspace.PROJECT, null, "rollback test");
        String original = Files.readString(projectRoot.resolve("README.md"));
        String content = "# Project\n\nbroken markdown\n```\nunclosed\n";
        String originalHash = com.lectureprocessor.fileagent.workspace.WorkspaceFileService
                .sha256(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String newHash = com.lectureprocessor.fileagent.workspace.WorkspaceFileService
                .sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FileChangeProposal proposal = proposals.insert(new FileChangeProposal(null, task.id(), 1,
                ProposalStatus.APPROVED, FileWorkspace.PROJECT, null, "rollback",
                "--- a/README.md\n", "ollama", "fake", null, null, "test", null,
                Instant.now(), Instant.now(), null));
        var changesRepo = new ProposedFileChangeRepository(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
        changesRepo.insert(proposal.id(), new com.lectureprocessor.fileagent.model.ProposedFileChange(
                null, proposal.id(), "README.md", com.lectureprocessor.fileagent.FileChangeType.MODIFY,
                originalHash, newHash, original, content, "test"));

        var result = application.apply(proposal.id());

        assertThat(result.applied()).isFalse();
        assertThat(result.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(Files.readString(projectRoot.resolve("README.md"))).isEqualTo(original);
        assertThat(service.get(task.id()).rollbackAttempts()).isGreaterThan(0);
    }

    @Test
    void cancellationIsHonouredBeforeAnyToolCall() {
        FileAgentTask task = service.create(FileWorkspace.PROJECT, null, GOAL);
        CancellationToken token = new CancellationToken();
        token.cancel();
        orchestrator.run(task.id(), token);
        assertThat(service.get(task.id()).status()).isEqualTo(FileAgentTaskStatus.CANCELLED);
        assertThat(service.toolTrace(task.id())).isEmpty();
    }

    @Test
    void llmUnavailableFailsWithClearStatus() {
        llm.setAvailable(false);
        FileAgentTask task = service.create(FileWorkspace.PROJECT, null, GOAL);
        orchestrator.run(task.id(), new CancellationToken());
        FileAgentTask failed = service.get(task.id());
        assertThat(failed.status()).isEqualTo(FileAgentTaskStatus.FAILED);
        assertThat(failed.error()).contains("LLM");
    }

    @Test
    void regenerationCreatesNewTaskAndInvalidatesOldProposal() {
        FileAgentTask task = service.create(FileWorkspace.PROJECT, null, GOAL);
        orchestrator.run(task.id(), new CancellationToken());
        FileChangeProposal old = service.proposals(task.id()).getFirst();
        FileAgentTask next = service.regenerate(task.id(), "try again");
        assertThat(next.id()).isNotEqualTo(task.id());
        awaitStatus(next.id(), FileAgentTaskStatus.AWAITING_APPROVAL);
        assertThat(service.proposal(old.id()).status()).isEqualTo(ProposalStatus.REJECTED);
    }

    private void awaitStatus(String taskId, FileAgentTaskStatus expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (service.get(taskId).status() == expected) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(service.get(taskId).status()).isEqualTo(expected);
    }

    private String planJson() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("reasoning", "LlmTaskSettingsService используется в README, docs и Java-классе");
        plan.put("queries", List.of("LlmTaskSettingsService", "model routing"));
        plan.put("symbols", List.of("LlmTaskSettingsService"));
        plan.put("readFiles", List.of("README.md", "docs/llm.md",
                "src/main/java/com/example/LlmTaskSettingsService.java"));
        plan.put("proposalKind", "DOC_UPDATE");
        plan.put("targetPaths", List.of("README.md", "docs/llm.md"));
        plan.put("notes", List.of());
        return objectMapper.writeValueAsString(plan);
    }

    private String proposalJson() {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("summary", "Обновлена документация маршрутизации моделей");
        proposal.put("changes", List.of(
                Map.of("path", "README.md", "action", "MODIFY",
                        "content", "# Project\n\nnew readme\n\nLlmTaskSettingsService выбирает модель по задаче.\n",
                        "reason", "README описывает маршрутизацию"),
                Map.of("path", "docs/llm.md", "action", "MODIFY",
                        "content", "# LLM\n\nnew routing docs\n\nFILE_AGENT имеет отдельную настройку.\n",
                        "reason", "docs описывает настройки")));
        proposal.put("notes", List.of("Проверить ссылки вручную"));
        return objectMapper.writeValueAsString(proposal);
    }

    private static HikariDataSource hikari(Path db) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + db.toAbsolutePath());
        config.setMaximumPoolSize(1);
        config.setPoolName("file-agent-test");
        return new HikariDataSource(config);
    }
}
