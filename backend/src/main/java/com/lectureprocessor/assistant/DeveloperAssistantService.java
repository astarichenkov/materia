package com.lectureprocessor.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.LlmOptions;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolRegistry;
import com.lectureprocessor.text.PromptTemplates;

/**
 * Developer assistant: answers questions about the project using the
 * documentation RAG plus read-only project context obtained through MCP tools.
 *
 * <p>The LLM call runs on a dedicated bounded executor, so a slow local model
 * never occupies the HTTP worker thread for the whole timeout and concurrent
 * requests are serialised instead of overloading the model.</p>
 */
@Service
public class DeveloperAssistantService {

    private static final Logger log = LoggerFactory.getLogger(DeveloperAssistantService.class);
    private static final int MAX_QUESTION_CHARS = 4000;
    private static final int MAX_CONTEXT_CHARS = 16000;
    private static final int TOOL_SUMMARY_CHARS = 600;

    private final DocumentationRetrievalService retrievalService;
    private final McpToolClient mcpClient;
    private final McpToolRegistry toolRegistry;
    private final LlmTaskResolver taskResolver;
    private final PromptTemplates prompts;
    private final AppProperties properties;
    private final AssistantSessionStore sessions;
    private final ThreadPoolTaskExecutor executor;

    public DeveloperAssistantService(DocumentationRetrievalService retrievalService,
            McpToolClient mcpClient, McpToolRegistry toolRegistry, LlmTaskResolver taskResolver,
            PromptTemplates prompts, AppProperties properties, AssistantSessionStore sessions,
            @Qualifier("assistantExecutor") ThreadPoolTaskExecutor executor) {
        this.retrievalService = retrievalService;
        this.mcpClient = mcpClient;
        this.toolRegistry = toolRegistry;
        this.taskResolver = taskResolver;
        this.prompts = prompts;
        this.properties = properties;
        this.sessions = sessions;
        this.executor = executor;
    }

    public AssistantAnswer ask(String sessionId, String rawMessage) {
        validate(rawMessage);
        String question = stripCommand(rawMessage.strip());
        if (question.isBlank()) {
            return commandOverview();
        }
        ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.DEVELOPER_ASSISTANT);
        if (!resolved.provider().isAvailable()) {
            throw new AssistantUnavailableException(
                    "LLM provider недоступен (" + resolved.providerId()
                            + ") — /help не может сгенерировать ответ");
        }

        List<DocSearchResult> retrieved = retrievalService.search(question);
        List<AssistantToolCall> toolCalls = new ArrayList<>();
        String branch = callTool("git_current_branch", Map.of(), toolCalls);
        String status = callTool("git_status", Map.of(), toolCalls);

        String system = prompts.get(PromptTemplates.DEVELOPER_HELP);
        String user = buildUserPrompt(sessionId, question, retrieved, branch, status);
        String answer = generate(resolved, system, user);

        List<AssistantCitation> citations = retrieved.stream()
                .map(result -> new AssistantCitation(
                        result.chunk().sourcePath(),
                        result.chunk().heading(),
                        result.chunk().sequence(),
                        result.score(),
                        DocumentationRetrievalService.excerpt(result.chunk().text())))
                .toList();
        boolean grounded = !retrieved.isEmpty() || toolCalls.stream().anyMatch(AssistantToolCall::ok);
        sessions.append(sessionId, "user", question);
        sessions.append(sessionId, "assistant", answer);
        return new AssistantAnswer(answer, citations, toolCalls, grounded);
    }

    public void clear(String sessionId) {
        sessions.clear(sessionId);
    }

    public List<ToolView> tools() {
        return toolRegistry.tools().stream()
                .map(tool -> new ToolView(tool.name(), tool.description()))
                .toList();
    }

    private AssistantAnswer commandOverview() {
        String toolList = toolRegistry.tools().stream()
                .map(tool -> "- `%s` — %s".formatted(tool.name(), tool.description()))
                .collect(Collectors.joining("\n"));
        String answer = """
                Я — ассистент разработчика Materia.

                Использование:
                - `/help <вопрос>` — ответ по документации проекта (README, docs) и контексту.

                Доступные read-only MCP-инструменты:
                %s

                Примеры: «Из чего состоит пайплайн обработки материала?», «Как запустить backend?»,
                «Какая сейчас git-ветка и есть ли незакоммиченные изменения?»
                """.formatted(toolList);
        return new AssistantAnswer(answer.strip(), List.of(), List.of(), true);
    }

    private String buildUserPrompt(String sessionId, String question, List<DocSearchResult> retrieved,
            String branch, String status) {
        StringBuilder context = new StringBuilder();
        context.append("Проект: Materia (локальный AI-пайплайн для видео, аудио и других материалов).\n\n");
        context.append("Текущий git-контекст (MCP):\n");
        context.append("git_current_branch: ").append(branch == null || branch.isBlank() ? "(нет данных)" : branch.strip()).append('\n');
        context.append("git_status:\n").append(status == null || status.isBlank() ? "(нет данных)" : status.strip()).append("\n\n");

        context.append("Фрагменты документации проекта:\n");
        if (retrieved.isEmpty()) {
            context.append("(ничего не найдено — документация не проиндексирована или релевантных фрагментов нет)\n");
        } else {
            int ref = 0;
            for (DocSearchResult result : retrieved) {
                ref++;
                context.append("[").append(result.chunk().sourcePath()).append("#")
                        .append(result.chunk().sequence()).append(" score=")
                        .append("%.3f".formatted(result.score())).append("]\n")
                        .append(result.chunk().text()).append("\n\n");
            }
        }
        if (context.length() > MAX_CONTEXT_CHARS) {
            context.setLength(MAX_CONTEXT_CHARS);
        }

        List<String> history = sessions.history(sessionId);
        StringBuilder user = new StringBuilder();
        if (!history.isEmpty()) {
            user.append("Недавний диалог:\n");
            history.forEach(line -> user.append(line).append('\n'));
            user.append('\n');
        }
        user.append(context).append("\nВопрос разработчика: ").append(question);
        return user.toString();
    }

    private String generate(ResolvedLlmTask resolved, String system, String user) {
        Future<String> future;
        try {
            future = executor.submit(() -> resolved.provider().chat(system, user, assistantOptions(resolved)));
        } catch (TaskRejectedException e) {
            throw new AssistantBusyException("Ассистент занят, попробуйте позже");
        }
        long timeoutMs = properties.assistant().timeout().toMillis();
        try {
            String answer = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (answer == null || answer.isBlank()) {
                throw new AssistantUnavailableException("Модель вернула пустой ответ");
            }
            return answer.strip();
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AssistantUnavailableException("Превышено время ожидания ответа модели");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new AssistantUnavailableException("Генерация прервана");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssistantUnavailableException("Ошибка генерации ответа", cause);
        }
    }

    private LlmOptions assistantOptions(ResolvedLlmTask resolved) {
        LlmOptions base = resolved.options();
        return new LlmOptions(base.model(), 0.1, 2048, 8192, base.seed(), base.thinking());
    }

    private String callTool(String toolName, Map<String, Object> arguments,
            List<AssistantToolCall> toolCalls) {
        try {
            McpToolCallResult result = mcpClient.call(toolName, arguments);
            String summary = result.ok()
                    ? truncate(result.text(), TOOL_SUMMARY_CHARS)
                    : "ошибка: " + truncate(result.text(), TOOL_SUMMARY_CHARS);
            toolCalls.add(new AssistantToolCall(toolName, result.ok(), summary));
            return result.ok() ? result.text() : "";
        } catch (RuntimeException e) {
            log.debug("MCP tool {} failed: {}", toolName, e.getMessage());
            toolCalls.add(new AssistantToolCall(toolName, false, e.getMessage()));
            return "";
        }
    }

    private void validate(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Вопрос не может быть пустым");
        }
        if (message.length() > MAX_QUESTION_CHARS) {
            throw new IllegalArgumentException(
                    "Вопрос слишком длинный (максимум " + MAX_QUESTION_CHARS + " символов)");
        }
    }

    static String stripCommand(String message) {
        String trimmed = message == null ? "" : message.strip();
        if (trimmed.regionMatches(true, 0, "/help", 0, 5)) {
            return trimmed.substring(5).strip();
        }
        return trimmed;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** Public description of an MCP tool for the UI/API. */
    public record ToolView(String name, String description) {
    }
}
