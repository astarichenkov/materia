package com.lectureprocessor.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Deterministic intent router. It maps a question plus available context to a
 * {@link SupportRoute} without any LLM call, so simple FAQ/diagnostic questions
 * never trigger expensive provider calls.
 *
 * <p>Routing is intentionally conservative: anything that cannot be classified
 * clearly falls back to {@link SupportIntent#GENERAL_FAQ} (documentation RAG).
 * The orchestrator may still combine evidence, but the route decides which tools
 * are even eligible to run.</p>
 */
@Component
public class SupportIntentRouter {

    private static final List<String> DIAGNOSTIC_MARKERS = List.of(
            "почему", "почему", "ошибк", "останов", "не обработал", "не законч", "сбой",
            "failed", "diagnost", "диагност", "стади", "стадия", "whisper", "транскриб",
            "распознав", "скачив", "прогресс", "job", "задач", "завис", "упал", "retry",
            "повтор", "checkpoint", "чекпоинт", "пайплайн", "pipeline");

    private static final List<String> TICKET_MARKERS = List.of(
            "тикет", "обращени", "ticket", "продолж", "истори", "переписк", "мо[йе] вопрос");

    private static final List<String> MODEL_MARKERS = List.of(
            "ollama", "deepseek", "модель", "провайдер", "llm", "gpt", "qwen", "connect",
            "подключ", "доступн");

    public SupportRoute route(String question, boolean hasTicket, boolean hasLecture) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean diagnostic = hasLecture || matches(text, DIAGNOSTIC_MARKERS);
        boolean ticketIntent = hasTicket || matches(text, TICKET_MARKERS);
        boolean model = matches(text, MODEL_MARKERS);

        SupportIntent intent;
        if (ticketIntent && diagnostic) {
            intent = SupportIntent.MIXED;
        } else if (ticketIntent) {
            intent = SupportIntent.TICKET_CONTEXT;
        } else if (diagnostic) {
            intent = SupportIntent.JOB_DIAGNOSTICS;
        } else {
            intent = SupportIntent.GENERAL_FAQ;
        }

        List<String> tools = new ArrayList<>();
        switch (intent) {
            case GENERAL_FAQ -> {
                // Documentation is retrieved directly; no diagnostics tools.
            }
            case JOB_DIAGNOSTICS -> addDiagnosticTools(tools, model);
            case TICKET_CONTEXT -> {
                tools.add("get_ticket");
                tools.add("list_ticket_messages");
                if (hasLecture) {
                    addDiagnosticTools(tools, model);
                }
            }
            case MIXED -> {
                tools.add("get_ticket");
                tools.add("list_ticket_messages");
                addDiagnosticTools(tools, model);
            }
        }
        return new SupportRoute(intent, tools);
    }

    private void addDiagnosticTools(List<String> tools, boolean model) {
        tools.add("get_job_status");
        tools.add("get_stage_runs");
        tools.add("get_job_diagnostics");
        if (model) {
            tools.add("get_model_health");
        }
    }

    private static boolean matches(String text, List<String> markers) {
        for (String marker : markers) {
            if (marker.contains("[")) {
                if (java.util.regex.Pattern.compile(marker).matcher(text).find()) {
                    return true;
                }
            } else if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
