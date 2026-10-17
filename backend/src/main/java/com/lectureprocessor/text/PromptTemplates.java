package com.lectureprocessor.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Loads versioned prompt templates from the classpath. Templates are separate
 * from code so prompt changes are reviewable and benchmarkable.
 */
@Component
public class PromptTemplates {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String get(String name) {
        return cache.computeIfAbsent(name, key -> {
            String resource = "/prompts/" + key + ".txt";
            try (InputStream in = PromptTemplates.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalArgumentException("Prompt template not found: " + resource);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read prompt template: " + resource, e);
            }
        });
    }

    /** Currently shipped prompt versions, used by the benchmark module. */
    public static final String CLEAN_TRANSCRIPT = "clean-transcript-v1";
    public static final String STRUCTURED_NOTES = "structured-notes-v1";
    public static final String SUMMARY = "summary-v1";
    public static final String RAG_ANSWER = "rag-answer-v1";
    public static final String BASIC_SUMMARY = "basic-summary-v1";

    /** Day 31 developer assistant prompt (project documentation + MCP context). */
    public static final String DEVELOPER_HELP = "developer-help-v1";

    /** Day 33 support assistant prompt (docs + diagnostics + ticket context). */
    public static final String SUPPORT_ASSISTANT = "support-assistant-v1";

    /** Day 34 autonomous file agent prompts. */
    public static final String FILE_AGENT_PLAN = "file-agent-plan-v1";
    public static final String FILE_AGENT_PROPOSAL = "file-agent-proposal-v1";

    /** Week 8 multilingual lecture translation prompt. */
    public static final String LECTURE_TRANSLATION = "lecture-translation-v1";

    /** Day 29 optimized prompt versions (more constrained, lower verbosity). */
    public static final String CLEAN_TRANSCRIPT_V2 = "clean-transcript-v2";
    public static final String STRUCTURED_NOTES_V2 = "structured-notes-v2";

    /** A versioned prompt template exposed to the benchmark UI. */
    public record Template(String name, String label, String description) {
    }

    /** Catalog of selectable prompt templates (single source of truth). */
    public List<Template> list() {
        return List.of(
                new Template(BASIC_SUMMARY, "basic-summary-v1",
                        "Базовое краткое резюме фрагмента (без строгой структуры)."),
                new Template(CLEAN_TRANSCRIPT, "clean-transcript-v1",
                        "Консервативная очистка ASR с сохранением терминов (baseline)."),
                new Template(STRUCTURED_NOTES, "structured-notes-v1",
                        "Структурированный конспект: заголовки, определения, резюме (рекомендованный)."),
                new Template(CLEAN_TRANSCRIPT_V2, "clean-transcript-v2",
                        "Более строгая очистка ASR (экспериментальный оптимизированный)."),
                new Template(STRUCTURED_NOTES_V2, "structured-notes-v2",
                        "Сжатый структурированный конспект (экспериментальный оптимизированный)."),
                new Template(LECTURE_TRANSLATION, "lecture-translation-v1",
                        "Сегментный перевод лекции с сохранением терминов и структуры (Week 8)."));
    }

    public boolean exists(String name) {
        return list().stream().anyMatch(t -> t.name().equals(name));
    }
}
