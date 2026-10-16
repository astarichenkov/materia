package com.lectureprocessor.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.lectureprocessor.llm.CloudCostService;
import com.lectureprocessor.llm.LlmResult;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.mcp.McpTool;
import com.lectureprocessor.mcp.McpToolRegistry;

/**
 * Day 33 support assistant orchestrator.
 *
 * <p>Resolves/creates the ticket, builds bounded evidence (documentation RAG +
 * allow-listed read-only MCP tools + ticket history), asks the SUPPORT_ASSISTANT
 * LLM task, validates the answer and persists the conversation, tool trace and
 * metrics. A deterministic path answers simple diagnostics questions when the
 * provider is unavailable, so the surface degrades gracefully instead of
 * failing.</p>
 */
@Service
public class SupportAssistantService {

    private static final Logger log = LoggerFactory.getLogger(SupportAssistantService.class);
    private static final String DETERMINISTIC_PROVIDER = "local-rules";
    private static final String DETERMINISTIC_MODEL = "deterministic";

    private final SupportProperties properties;
    private final SupportTicketService tickets;
    private final SupportContextBuilder contextBuilder;
    private final SupportPromptBuilder promptBuilder;
    private final SupportAnswerValidator validator;
    private final SupportDeterministicAnswerer deterministic;
    private final LlmTaskResolver taskResolver;
    private final CloudCostService costs;
    private final McpToolRegistry toolRegistry;
    private final ThreadPoolTaskExecutor executor;

    public SupportAssistantService(SupportProperties properties, SupportTicketService tickets,
            SupportContextBuilder contextBuilder, SupportPromptBuilder promptBuilder,
            SupportAnswerValidator validator, SupportDeterministicAnswerer deterministic,
            LlmTaskResolver taskResolver, CloudCostService costs, McpToolRegistry toolRegistry,
            @Qualifier("assistantExecutor") ThreadPoolTaskExecutor executor) {
        this.properties = properties;
        this.tickets = tickets;
        this.contextBuilder = contextBuilder;
        this.promptBuilder = promptBuilder;
        this.validator = validator;
        this.deterministic = deterministic;
        this.taskResolver = taskResolver;
        this.costs = costs;
        this.toolRegistry = toolRegistry;
        this.executor = executor;
    }

    public SupportAnswer ask(SupportAskRequest request, SupportPrincipal principal) {
        requireEnabled();
        String question = validate(request);
        SupportTicket ticket = resolveTicket(request, principal);
        String lectureId = request.lectureId() != null && !request.lectureId().isBlank()
                ? request.lectureId() : ticket.lectureId();
        SupportPrincipal who = principal == null ? new SupportPrincipal(null, null) : principal;

        List<SupportMessage> history = tickets.history(ticket.id(), who);
        SupportContext context = contextBuilder.build(ticket, lectureId, question, who,
                request.ticketId() != null);

        // Persist the user's turn before generating so history is never lost.
        SupportMessage userMessage = new SupportMessage(null, ticket.id(), SupportMessageRole.USER,
                question, java.time.Instant.now(), lectureId, null, List.of(), List.of(),
                null, null, null, null, null, null, null, null, null, null, null);
        tickets.saveMessage(ticket.id(), userMessage, who);

        long start = System.currentTimeMillis();
        Generation generation = generate(context, history, question);
        long duration = System.currentTimeMillis() - start;

        String provider = generation.provider();
        String model = generation.model();
        boolean cloud = generation.cloud();
        Double estimatedCost = null;
        String currency = null;
        if (cloud && generation.inputTokens() != null && generation.outputTokens() != null) {
            var estimate = costs.estimate(generation.inputTokens(), generation.outputTokens(),
                    generation.cacheHitTokens(), generation.cacheMissTokens());
            estimatedCost = estimate.amount();
            currency = estimate.currency();
        }

        List<String> limitations = new ArrayList<>(generation.limitations());
        if (context.evidence().stream().noneMatch(e -> "DOC".equals(e.kind()))) {
            limitations.add("Документация не проиндексирована или релевантных фрагментов не найдено.");
        }
        if (needsDiagnostics(context) && !context.diagnosticsAvailable()) {
            limitations.add("Диагностические данные по задаче отсутствуют в БД.");
        }
        if (!context.grounded()) {
            limitations.add("Нет подтверждённых данных — ответ не является диагностикой.");
        }

        SupportMessage assistantMessage = new SupportMessage(null, ticket.id(),
                SupportMessageRole.ASSISTANT, generation.answer(), java.time.Instant.now(), lectureId,
                null, context.citations(), context.toolTraces(), provider, model, context.grounded(),
                duration, generation.inputTokens(), generation.outputTokens(), estimatedCost, currency,
                context.toolTraces().size(), failures(context.toolTraces()), null);
        SupportMessage saved = tickets.saveMessage(ticket.id(), assistantMessage, who);

        long toolCallsCount = context.toolTraces().size();
        List<SupportToolCall> invocations = context.toolInvocations().stream()
                .map(call -> new SupportToolCall(null, saved.id(), ticket.id(), call.toolName(),
                        call.ok(), call.durationMs(), call.summary(), call.createdAt()))
                .toList();
        tickets.recordToolCalls(invocations);

        return new SupportAnswer(generation.answer(), generation.suggestedActions(), context.citations(),
                context.toolTraces(), ticket.id(), saved.id(), lectureId, null, provider, model,
                context.grounded(), dedup(limitations), generation.error(), duration,
                generation.inputTokens(), generation.outputTokens(), estimatedCost, currency, cloud);
    }

    /** Read-only allow-listed tools exposed by the support profile (for the UI). */
    public List<ToolView> tools() {
        List<String> allowed = properties.allowedTools() == null ? List.of() : properties.allowedTools();
        return toolRegistry.tools().stream()
                .filter(tool -> allowed.contains(tool.name()))
                .map(tool -> new ToolView(tool.name(), tool.description()))
                .toList();
    }

    private Generation generate(SupportContext context, List<SupportMessage> history, String question) {
        String system = promptBuilder.system();
        String user = promptBuilder.user(context, history, question);
        ResolvedLlmTask resolved;
        try {
            resolved = taskResolver.resolve(LlmTaskType.SUPPORT_ASSISTANT);
        } catch (RuntimeException e) {
            return deterministicOrFail(context, e.getMessage());
        }
        if (resolved == null || !resolved.provider().isAvailable()) {
            return deterministicOrFail(context, "LLM provider недоступен");
        }
        try {
            LlmResult result = call(resolved, system, user);
            var validated = validator.validate(result.content(), context.grounded());
            return new Generation(validated.answer(), validated.suggestedActions(),
                    validated.limitations(), resolved.providerId(), result.content() == null ? null
                            : resolved.model(), result.inputTokens(), result.outputTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), result.cloud(), null);
        } catch (SupportUnavailableException e) {
            return deterministicOrFail(context, e.getMessage());
        } catch (RuntimeException e) {
            log.debug("Support LLM call failed: {}", e.getMessage());
            return deterministicOrFail(context, e.getMessage());
        }
    }

    private Generation deterministicOrFail(SupportContext context, String reason) {
        var fallback = deterministic.answer(context);
        if (fallback.isPresent()) {
            var answer = fallback.get();
            List<String> limitations = new ArrayList<>(answer.limitations());
            limitations.add("Ответ сформирован детерминированно без LLM: " + reason);
            return new Generation(answer.answer(), answer.suggestedActions(), limitations,
                    DETERMINISTIC_PROVIDER, DETERMINISTIC_MODEL, null, null, 0, 0, false, null);
        }
        throw new SupportUnavailableException(
                "Не удалось сформировать ответ: " + (reason == null ? "LLM недоступна" : reason));
    }

    private LlmResult call(ResolvedLlmTask resolved, String system, String user) {
        Future<LlmResult> future;
        try {
            future = executor.submit(() -> resolved.provider().generate(system, user, resolved.options()));
        } catch (TaskRejectedException e) {
            throw new SupportUnavailableException("Ассистент занят, попробуйте позже");
        }
        long timeoutMs = Math.max(1000, properties.timeout().toMillis());
        try {
            LlmResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result == null || result.content() == null || result.content().isBlank()) {
                throw new SupportUnavailableException("Модель вернула пустой ответ");
            }
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new SupportUnavailableException("Превышено время ожидания ответа модели");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new SupportUnavailableException("Генерация прервана");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new SupportUnavailableException("Ошибка генерации ответа", cause);
        }
    }

    private SupportTicket resolveTicket(SupportAskRequest request, SupportPrincipal principal) {
        if (request.ticketId() != null) {
            return tickets.get(request.ticketId(), principal);
        }
        String lectureId = request.lectureId();
        if (lectureId != null && !lectureId.isBlank()) {
            return tickets.getOrCreateForLecture(lectureId, principal);
        }
        String subject = request.question() == null ? "Обращение в поддержку"
                : request.question().strip();
        if (subject.length() > 120) {
            subject = subject.substring(0, 120);
        }
        return tickets.create(subject, "GENERAL", null, principal);
    }

    private String validate(SupportAskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new SupportRequestException("Вопрос не может быть пустым");
        }
        String question = request.question().strip();
        int max = Math.max(100, properties.maxQuestionChars());
        if (question.length() > max) {
            throw new SupportRequestException("Вопрос слишком длинный (максимум " + max + " символов)");
        }
        return question;
    }

    private static boolean needsDiagnostics(SupportContext context) {
        return context.route().intent() == SupportIntent.JOB_DIAGNOSTICS
                || context.route().intent() == SupportIntent.MIXED;
    }

    private static int failures(List<SupportToolTrace> traces) {
        return (int) traces.stream().filter(trace -> !trace.ok()).count();
    }

    private static List<String> dedup(List<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).distinct().toList();
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new SupportRequestException("Поддержка отключена (app.support.enabled=false)");
        }
    }

    private record Generation(String answer, List<String> suggestedActions, List<String> limitations,
            String provider, String model, Integer inputTokens, Integer outputTokens, int cacheHitTokens,
            int cacheMissTokens, boolean cloud, String error) {
    }

    /** Tool description for the UI/API. */
    public record ToolView(String name, String description) {
    }
}
