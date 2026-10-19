package com.lectureprocessor.scenario;

import java.util.List;
import java.util.Locale;

import com.lectureprocessor.processing.DetailLevel;
import com.lectureprocessor.processing.MaterialType;
import com.lectureprocessor.processing.OutputFormat;
import com.lectureprocessor.text.AssemblyPolicy;
import com.lectureprocessor.text.PromptTemplates;

/**
 * Authoritative catalogue of what a user can do with a material.
 *
 * <p>A scenario is not a material type and not a document format. It answers the
 * question «Что сделать с материалом?», and recommends a format, a prompt
 * template, a detail level, an assembly policy and (when needed) translation.
 * The user may override the recommended values; the scenario is then kept as
 * the intent and compatibility warnings explain any mismatch.</p>
 *
 * <p>IDs are stable and persisted as strings. Never rename an existing ID: the
 * database stores it in {@code lecture_processing_profile.scenario}.</p>
 */
public enum ProcessingScenario {

    NOTES("notes", "Конспект",
            "Выделить темы, идеи, факты и выводы из лекции, вебинара или подкаста.",
            "Структурированный конспект с основными темами и выводами.",
            ScenarioCategory.UNDERSTANDING, OutputFormat.DETAILED_NOTES,
            PromptTemplates.SCENARIO_NOTES, DetailLevel.STANDARD, DocumentSource.LLM,
            AssemblyPolicy.SEMANTIC, true, false,
            List.of(MaterialType.AUTO, MaterialType.ACADEMIC_LECTURE, MaterialType.WEBINAR,
                    MaterialType.PRESENTATION, MaterialType.DISCUSSION)),

    ACADEMIC_NOTES("academic-notes", "Академический конспект",
            "Подробный конспект с определениями, аргументами и логикой изложения.",
            "Развёрнутый учебный конспект с определениями и выводами.",
            ScenarioCategory.STRUCTURED, OutputFormat.DETAILED_NOTES,
            PromptTemplates.NOTES_ACADEMIC, DetailLevel.DETAILED, DocumentSource.LLM,
            AssemblyPolicy.SEMANTIC, true, false,
            List.of(MaterialType.AUTO, MaterialType.ACADEMIC_LECTURE, MaterialType.PRESENTATION)),

    SHORT_SUMMARY("short-summary", "Краткий пересказ",
            "Коротко передать содержание видео, интервью, фильма или стрима.",
            "Краткое содержание, события и важные детали в порядке изложения.",
            ScenarioCategory.UNDERSTANDING, OutputFormat.SHORT_SUMMARY,
            PromptTemplates.SCENARIO_SUMMARY, DetailLevel.STANDARD, DocumentSource.LLM,
            AssemblyPolicy.CHRONOLOGICAL, false, false,
            List.of(MaterialType.values())),

    RECIPE("recipe", "Рецепт",
            "Превратить кулинарное видео в ингредиенты и последовательность приготовления.",
            "Рецепт с количествами и шагами — только из материала.",
            ScenarioCategory.STRUCTURED, OutputFormat.RECIPE,
            PromptTemplates.SCENARIO_RECIPE, DetailLevel.DETAILED, DocumentSource.LLM,
            AssemblyPolicy.STRUCTURED, false, false,
            List.of(MaterialType.values())),

    DIALOGUE("dialogue", "Диалоги",
            "Получить разговоры и реплики в исходном порядке.",
            "Реплики по порядку, без выдуманных участников, с временными метками.",
            ScenarioCategory.STRUCTURED, OutputFormat.DIALOGUE,
            PromptTemplates.SCENARIO_DIALOGUE, DetailLevel.DETAILED, DocumentSource.LLM,
            AssemblyPolicy.DIALOGUE, false, false,
            List.of(MaterialType.values())),

    INSTRUCTION("instruction", "Инструкция",
            "Собрать пошаговые действия, инструменты и проверку результата.",
            "Инструкция с целью, шагами и предупреждениями из материала.",
            ScenarioCategory.STRUCTURED, OutputFormat.STEP_BY_STEP,
            PromptTemplates.SCENARIO_INSTRUCTION, DetailLevel.STANDARD, DocumentSource.LLM,
            AssemblyPolicy.STRUCTURED, false, false,
            List.of(MaterialType.values())),

    ARTICLE("article", "Статья",
            "Связный текст с введением, разделами и заключением.",
            "Готовая статья по содержанию материала.",
            ScenarioCategory.STRUCTURED, OutputFormat.ARTICLE,
            PromptTemplates.NOTES_ARTICLE, DetailLevel.STANDARD, DocumentSource.LLM,
            AssemblyPolicy.CHRONOLOGICAL, false, false,
            List.of(MaterialType.values())),

    FAQ("faq", "FAQ",
            "Собрать только реальные вопросы и ответы из материала.",
            "Список вопросов и ответов без придуманных пунктов.",
            ScenarioCategory.STRUCTURED, OutputFormat.FAQ,
            PromptTemplates.NOTES_FAQ, DetailLevel.STANDARD, DocumentSource.LLM,
            AssemblyPolicy.SEMANTIC, false, false,
            List.of(MaterialType.values())),

    MEETING_PROTOCOL("meeting-protocol", "Протокол встречи",
            "Зафиксировать повестку, решения, задачи и ответственных.",
            "Протокол встречи без выдуманных участников и решений.",
            ScenarioCategory.STRUCTURED, OutputFormat.MEETING_PROTOCOL,
            PromptTemplates.NOTES_MEETING, DetailLevel.STANDARD, DocumentSource.LLM,
            AssemblyPolicy.CHRONOLOGICAL, false, false,
            List.of(MaterialType.values())),

    METHODICAL_GUIDE("methodical-guide", "Методическое пособие",
            "Собрать учебные цели, порядок изучения, задания и вопросы.",
            "Методическое пособие по материалу.",
            ScenarioCategory.STRUCTURED, OutputFormat.METHODICAL_GUIDE,
            PromptTemplates.NOTES_METHODICAL, DetailLevel.DETAILED, DocumentSource.LLM,
            AssemblyPolicy.SEMANTIC, false, false,
            List.of(MaterialType.AUTO, MaterialType.ACADEMIC_LECTURE,
                    MaterialType.PRACTICAL_TUTORIAL, MaterialType.PRESENTATION)),

    CLEAN_TRANSCRIPT("clean-transcript", "Очищенная транскрипция",
            "Исправить пунктуацию и абзацы, сохранив смысл и порядок речи.",
            "Читаемый текст распознавания без пересказа.",
            ScenarioCategory.TRANSCRIPT, OutputFormat.CLEAN_TRANSCRIPT,
            PromptTemplates.CLEAN_TRANSCRIPT_V2, DetailLevel.STANDARD, DocumentSource.LLM,
            AssemblyPolicy.CHRONOLOGICAL, false, false,
            List.of(MaterialType.values())),

    VERBATIM("verbatim", "Без изменений",
            "Получить текст распознавания без пересказа, очистки и LLM-редактирования.",
            "Дословная транскрипция с временными метками, без обработки моделью.",
            ScenarioCategory.TRANSCRIPT, OutputFormat.VERBATIM_TRANSCRIPT,
            null, DetailLevel.STANDARD, DocumentSource.TRANSCRIPT,
            AssemblyPolicy.LOSSLESS, false, false,
            List.of(MaterialType.values())),

    TRANSLATION("translation", "Только перевод",
            "Перевести материал, сохранив сегменты и временные метки, без конспекта.",
            "Переведённая транскрипция; оригинал остаётся доступен.",
            ScenarioCategory.LANGUAGE, OutputFormat.TRANSLATION_ONLY,
            null, DetailLevel.STANDARD, DocumentSource.TRANSCRIPT,
            AssemblyPolicy.LOSSLESS, false, true,
            List.of(MaterialType.values()));

    private final String id;
    private final String title;
    private final String description;
    private final String outputHint;
    private final ScenarioCategory category;
    private final OutputFormat recommendedFormat;
    private final String recommendedPrompt;
    private final DetailLevel recommendedDetail;
    private final DocumentSource documentSource;
    private final AssemblyPolicy assemblyPolicy;
    private final boolean includeSummary;
    private final boolean translationRecommended;
    private final List<MaterialType> supportedTypes;

    ProcessingScenario(String id, String title, String description, String outputHint,
            ScenarioCategory category, OutputFormat recommendedFormat,
            String recommendedPrompt, DetailLevel recommendedDetail,
            DocumentSource documentSource, AssemblyPolicy assemblyPolicy,
            boolean includeSummary, boolean translationRecommended,
            List<MaterialType> supportedTypes) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.outputHint = outputHint;
        this.category = category;
        this.recommendedFormat = recommendedFormat;
        this.recommendedPrompt = recommendedPrompt;
        this.recommendedDetail = recommendedDetail;
        this.documentSource = documentSource;
        this.assemblyPolicy = assemblyPolicy;
        this.includeSummary = includeSummary;
        this.translationRecommended = translationRecommended;
        this.supportedTypes = List.copyOf(supportedTypes);
    }

    public String id() {
        return id;
    }

    /** Bumped whenever the scenario behaviour/contract changes. */
    public int version() {
        return 1;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public String outputHint() {
        return outputHint;
    }

    public ScenarioCategory category() {
        return category;
    }

    public OutputFormat recommendedFormat() {
        return recommendedFormat;
    }

    public String recommendedPrompt() {
        return recommendedPrompt;
    }

    public DetailLevel recommendedDetail() {
        return recommendedDetail;
    }

    public DocumentSource documentSource() {
        return documentSource;
    }

    public boolean usesLlm() {
        return documentSource.usesLlm();
    }

    public AssemblyPolicy assemblyPolicy() {
        return assemblyPolicy;
    }

    public boolean includeSummary() {
        return includeSummary;
    }

    public boolean translationRecommended() {
        return translationRecommended;
    }

    public List<MaterialType> supportedTypes() {
        return supportedTypes;
    }

    public boolean supports(MaterialType type) {
        return type == null || type == MaterialType.AUTO || supportedTypes.contains(type);
    }

    /** Label of the resulting document, e.g. «Рецепт» or «Транскрипция». */
    public String documentLabel() {
        return recommendedFormat.documentLabel();
    }

    public String promptLabel() {
        return usesLlm() && recommendedPrompt != null ? recommendedPrompt : "не применяется";
    }

    /** Never returns {@code null} for an unknown ID: callers validate explicitly. */
    public static ProcessingScenario fromId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (ProcessingScenario scenario : values()) {
            if (scenario.id.equals(normalized) || scenario.name().equalsIgnoreCase(normalized)) {
                return scenario;
            }
        }
        return null;
    }

    /** Human-readable list of supported material types. */
    public String supportedTypesLabel() {
        return supportedTypes.stream().map(MaterialType::label)
                .reduce((a, b) -> a + ", " + b).orElse("");
    }
}
