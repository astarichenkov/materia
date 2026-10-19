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
    /** User-owned template bodies registered from the database at startup/update. */
    private final Map<String, String> custom = new ConcurrentHashMap<>();

    public String get(String name) {
        String customBody = custom.get(name);
        if (customBody != null) {
            return customBody;
        }
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

    /** Registers (or replaces) a user-owned template body. */
    public void registerCustom(String name, String body) {
        if (name == null || body == null || body.isBlank()) {
            return;
        }
        custom.put(name, body.strip());
    }

    public void unregisterCustom(String name) {
        custom.remove(name);
    }

    public String builtIn(String name) {
        String previous = custom.remove(name);
        try {
            return get(name);
        } finally {
            if (previous != null) {
                custom.put(name, previous);
            }
        }
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

    /** PROMPT-1B output format presets (one template per requested document format). */
    public static final String NOTES_ACADEMIC = "notes-academic-v1";
    public static final String NOTES_BRIEF = "notes-brief-v1";
    public static final String NOTES_HOWTO = "notes-howto-v1";
    public static final String NOTES_INTERVIEW = "notes-interview-v1";
    public static final String NOTES_METHODICAL = "notes-methodical-v1";
    public static final String NOTES_ARTICLE = "notes-article-v1";
    public static final String NOTES_FAQ = "notes-faq-v1";
    public static final String NOTES_MEETING = "notes-meeting-v1";

    /** Materia scenario prompts (universal content processing). */
    public static final String SCENARIO_NOTES = "scenario-notes-v1";
    public static final String SCENARIO_SUMMARY = "scenario-summary-v1";
    public static final String SCENARIO_RECIPE = "scenario-recipe-v1";
    public static final String SCENARIO_DIALOGUE = "scenario-dialogue-v1";
    public static final String SCENARIO_INSTRUCTION = "scenario-instruction-v1";

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
                        "Сегментный перевод лекции с сохранением терминов и структуры (Week 8)."),
                new Template(NOTES_ACADEMIC, "notes-academic-v1",
                        "Подробный конспект академической лекции: определения, аргументы, выводы."),
                new Template(NOTES_BRIEF, "notes-brief-v1",
                        "Краткий конспект: только главные идеи и выводы."),
                new Template(NOTES_HOWTO, "notes-howto-v1",
                        "Пошаговая инструкция: шаги, команды, параметры, ожидаемый результат."),
                new Template(NOTES_INTERVIEW, "notes-interview-v1",
                        "Вопросы и ответы интервью без выдуманной атрибуции реплик."),
                new Template(NOTES_METHODICAL, "notes-methodical-v1",
                        "Методическое пособие: цели, порядок изучения, задания."),
                new Template(NOTES_ARTICLE, "notes-article-v1",
                        "Связная статья по материалу лекции."),
                new Template(NOTES_FAQ, "notes-faq-v1",
                        "FAQ: вопросы и ответы строго по материалу."),
                new Template(NOTES_MEETING, "notes-meeting-v1",
                        "Протокол встречи: повестка, решения, задачи, открытые вопросы."),
                new Template(SCENARIO_NOTES, "scenario-notes-v1",
                        "Конспект материала: темы, идеи, факты и выводы только из источника."),
                new Template(SCENARIO_SUMMARY, "scenario-summary-v1",
                        "Краткий пересказ: содержание и события в порядке изложения."),
                new Template(SCENARIO_RECIPE, "scenario-recipe-v1",
                        "Рецепт: ингредиенты, количества и шаги без выдуманных деталей."),
                new Template(SCENARIO_DIALOGUE, "scenario-dialogue-v1",
                        "Диалоги: реплики в исходном порядке без выдуманных участников."),
                new Template(SCENARIO_INSTRUCTION, "scenario-instruction-v1",
                        "Инструкция: цель, подготовка, шаги и проверка результата."));
    }

    public boolean exists(String name) {
        return custom.containsKey(name) || list().stream().anyMatch(t -> t.name().equals(name));
    }

    /** True when the template body comes from the database (user copy). */
    public boolean isCustom(String name) {
        return custom.containsKey(name);
    }
}
