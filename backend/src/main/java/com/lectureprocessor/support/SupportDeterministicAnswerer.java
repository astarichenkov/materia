package com.lectureprocessor.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic (LLM-free) answer for clear job-status questions. Used when the
 * provider is unavailable, or to answer a simple diagnostics lookup cheaply.
 *
 * <p>Only persisted facts are used; when nothing useful is found it returns
 * empty so the caller can be honest about the missing evidence.</p>
 */
@Component
public class SupportDeterministicAnswerer {

    private final ObjectMapper objectMapper;

    public SupportDeterministicAnswerer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<SupportAnswerValidator.ValidatedAnswer> answer(SupportContext context) {
        if (!context.diagnosticsAvailable()) {
            return Optional.empty();
        }
        JsonNode diagnostics = findDiagnostics(context).orElse(null);
        if (diagnostics == null) {
            return Optional.empty();
        }
        String lectureStatus = text(diagnostics, "lectureStatus");
        String jobState = text(diagnostics, "jobState");
        String currentStage = text(diagnostics, "currentStage");
        String lectureError = text(diagnostics, "lectureError");
        String jobError = text(diagnostics, "jobError");

        FailedStage failed = firstFailedStage(diagnostics.path("stages"));
        StringBuilder answer = new StringBuilder();
        List<String> actions = new ArrayList<>();
        List<String> limitations = new ArrayList<>();

        if (failed != null) {
            answer.append("Обработка остановилась на стадии ").append(failed.stage())
                    .append(" (состояние ").append(failed.state()).append(").");
            String error = failed.error() != null ? failed.error()
                    : (jobError != null ? jobError : lectureError);
            if (error != null && !error.isBlank()) {
                answer.append(" Причина по сохранённым данным: ").append(error).append('.');
            }
            if (hasCompleted(diagnostics.path("stages"), "TRANSCRIPTION")
                    && !"TRANSCRIPTION".equals(failed.stage())) {
                answer.append(" Распознавание речи уже завершено, поэтому повторять его не требуется.");
                actions.add("Повторить обработку с этапа " + failed.stage());
            } else {
                actions.add("Проверить журнал приложения и повторить стадию " + failed.stage());
            }
        } else if ("COMPLETED".equalsIgnoreCase(lectureStatus) || "SUCCEEDED".equalsIgnoreCase(jobState)) {
            answer.append("Обработка материала завершена успешно.");
            actions.add("Открыть конспект и экспортированный документ");
        } else if (lectureStatus != null) {
            answer.append("Статус материала: ").append(lectureStatus);
            if (currentStage != null) {
                answer.append(", текущая стадия ").append(currentStage);
            }
            answer.append('.');
        } else {
            return Optional.empty();
        }
        return Optional.of(new SupportAnswerValidator.ValidatedAnswer(answer.toString(), actions, limitations));
    }

    private Optional<JsonNode> findDiagnostics(SupportContext context) {
        for (SupportCitation citation : context.citations()) {
            if ("get_job_diagnostics".equals(citation.source())) {
                for (SupportEvidence evidence : context.evidence()) {
                    if (evidence.citation() == citation) {
                        try {
                            return Optional.ofNullable(objectMapper.readTree(evidence.content()));
                        } catch (RuntimeException e) {
                            return Optional.empty();
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static FailedStage firstFailedStage(JsonNode stages) {
        if (stages == null || !stages.isArray()) {
            return null;
        }
        for (JsonNode stage : stages) {
            String state = text(stage, "state");
            if ("FAILED".equals(state) || "CANCELLED".equals(state)) {
                return new FailedStage(text(stage, "stage"), state, text(stage, "error"));
            }
        }
        return null;
    }

    private static boolean hasCompleted(JsonNode stages, String name) {
        if (stages == null || !stages.isArray()) {
            return false;
        }
        for (JsonNode stage : stages) {
            if (name.equals(text(stage, "stage")) && "COMPLETED".equals(text(stage, "state"))) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isTextual() ? null : value.asText();
    }

    private record FailedStage(String stage, String state, String error) {
    }
}
