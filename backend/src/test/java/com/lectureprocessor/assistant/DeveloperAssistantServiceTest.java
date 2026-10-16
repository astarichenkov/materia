package com.lectureprocessor.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolRegistry;
import com.lectureprocessor.mcp.McpToolResult;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.FixedTaskResolver;
import com.lectureprocessor.support.TestProperties;
import com.lectureprocessor.text.PromptTemplates;

class DeveloperAssistantServiceTest {

    private final DocumentationRetrievalService retrieval = mock(DocumentationRetrievalService.class);
    private final FakeLlmProvider llm = new FakeLlmProvider();
    private final AssistantSessionStore sessions = new AssistantSessionStore(TestProperties.defaults("."));

    private final McpToolClient mcpClient = (name, arguments) -> switch (name) {
        case "git_current_branch" -> new McpToolCallResult(true, "feature/week7-day31");
        case "git_status" -> new McpToolCallResult(true, "## feature/week7-day31\n M src/App.java");
        default -> new McpToolCallResult(false, "unknown tool");
    };

    private static final McpTool BRANCH_TOOL = new McpTool() {
        @Override
        public String name() {
            return "git_current_branch";
        }

        @Override
        public String description() {
            return "current branch";
        }

        @Override
        public java.util.Map<String, Object> inputSchema() {
            return java.util.Map.of();
        }

        @Override
        public McpToolResult invoke(java.util.Map<String, Object> arguments) {
            return McpToolResult.ok("main");
        }
    };

    private ThreadPoolTaskExecutor executor;
    private DeveloperAssistantService service;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();
        service = new DeveloperAssistantService(retrieval, mcpClient, new McpToolRegistry(List.of(BRANCH_TOOL)),
                new FixedTaskResolver(llm), new PromptTemplates(), TestProperties.defaults("."), sessions, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void answersWithDocumentationAndMcpContext() {
        when(retrieval.search(anyString())).thenReturn(List.of(new DocSearchResult(
                new DocChunk(1, "docs/pipeline.md", "Pipeline", "Этапы", 0,
                        "Пайплайн состоит из стадий.", new float[32]),
                0.87)));

        AssistantAnswer answer = service.ask("s1", "/help Из чего состоит пайплайн?");

        assertThat(answer.answer()).contains("ANSWER:");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.citations().getFirst().sourcePath()).isEqualTo("docs/pipeline.md");
        assertThat(answer.toolCalls()).extracting(AssistantToolCall::name)
                .containsExactly("git_current_branch", "git_status");
        assertThat(answer.grounded()).isTrue();
    }

    @Test
    void bareHelpReturnsCommandOverviewWithoutCallingLlm() {
        AssistantAnswer answer = service.ask("s2", "/help");

        assertThat(answer.answer()).contains("/help").contains("git_current_branch");
        assertThat(llm.chatCalls()).isEmpty();
        verifyNoInteractions(retrieval);
    }

    @Test
    void plainQuestionWithoutCommandStillWorks() {
        when(retrieval.search(anyString())).thenReturn(List.of());

        AssistantAnswer answer = service.ask("s3", "Как запустить backend?");

        assertThat(answer.answer()).contains("ANSWER:");
    }

    @Test
    void unavailableModelRaisesAssistantUnavailable() {
        llm.setAvailable(false);
        assertThatThrownBy(() -> service.ask("s4", "/help вопрос"))
                .isInstanceOf(AssistantUnavailableException.class);
    }

    @Test
    void blankQuestionIsRejected() {
        assertThatThrownBy(() -> service.ask("s5", "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stripCommandIsCaseInsensitive() {
        assertThat(DeveloperAssistantService.stripCommand("/HELP abc")).isEqualTo("abc");
        assertThat(DeveloperAssistantService.stripCommand("  question")).isEqualTo("question");
    }

    @Test
    void toolFailuresAreRecordedButDoNotBreakAnswer() {
        McpToolClient failing = (name, arguments) -> new McpToolCallResult(false, "git недоступен");
        DeveloperAssistantService failingService = new DeveloperAssistantService(retrieval, failing,
                new McpToolRegistry(List.of(BRANCH_TOOL)), new FixedTaskResolver(llm), new PromptTemplates(),
                TestProperties.defaults("."), sessions, executor);
        when(retrieval.search(anyString())).thenReturn(List.of());

        AssistantAnswer answer = failingService.ask("s6", "/help вопрос");

        assertThat(answer.toolCalls()).allSatisfy(call -> assertThat(call.ok()).isFalse());
        assertThat(answer.answer()).contains("ANSWER:");
    }

    @Test
    void sessionHistoryIsKept() {
        when(retrieval.search(anyString())).thenReturn(List.of());
        service.ask("thread", "/help первый");
        service.ask("thread", "/help второй");

        List<String> history = sessions.history("thread");
        assertThat(history).hasSize(4);
    }

    @Test
    void toolsExposeRegisteredCatalog() {
        assertThat(service.tools()).extracting(DeveloperAssistantService.ToolView::name)
                .containsExactly("git_current_branch");
    }
}
