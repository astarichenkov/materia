package com.lectureprocessor.processing;

import java.util.Locale;

/**
 * Requested document format. Each format resolves to one versioned prompt
 * template; the reliability rules live in the prompt, not in copied Java code.
 * Values are persisted by name and must stay stable.
 */
public enum OutputFormat {
    DETAILED_NOTES("Подробный конспект",
            "Развёрнутый конспект с определениями, аргументами и выводами.",
            "notes-academic-v1", false),
    BRIEF_NOTES("Краткий конспект",
            "Главные идеи и ключевые выводы в компактной структуре.",
            "notes-brief-v1", false),
    STEP_BY_STEP("Пошаговая инструкция",
            "Последовательность действий с командами и ожидаемым результатом.",
            "notes-howto-v1", false),
    CLEAN_TRANSCRIPT("Очищенная транскрипция",
            "Близкий к оригиналу текст с исправленной пунктуацией и абзацами.",
            "clean-transcript-v2", true),
    METHODICAL_GUIDE("Методическое пособие",
            "Учебные цели, порядок изучения, задания и контрольные вопросы.",
            "notes-methodical-v1", false),
    ARTICLE("Статья",
            "Связный текст с введением, разделами и заключением.",
            "notes-article-v1", false),
    FAQ("FAQ",
            "Частые вопросы и ответы, основанные только на материале.",
            "notes-faq-v1", false),
    MEETING_PROTOCOL("Протокол встречи",
            "Повестка, решения, задачи, ответственные и сроки из материала.",
            "notes-meeting-v1", false),
    INTERVIEW_QA("Вопросы и ответы",
            "Вопросы ведущего и ответы участников без выдуманных реплик.",
            "notes-interview-v1", false),
    SHORT_SUMMARY("Краткий пересказ",
            "Содержание и развитие событий/идей без академической структуры.",
            "scenario-summary-v1", false),
    RECIPE("Рецепт",
            "Ингредиенты, количества и последовательность приготовления из материала.",
            "scenario-recipe-v1", false),
    DIALOGUE("Диалоги",
            "Реплики в исходном порядке без выдуманной атрибуции говорящих.",
            "scenario-dialogue-v1", false),
    VERBATIM_TRANSCRIPT("Транскрипция без изменений",
            "Текст распознавания с временными метками, без LLM-редактирования.",
            null, true),
    TRANSLATION_ONLY("Только перевод",
            "Переведённая транскрипция с сохранением сегментов и времени.",
            null, false);

    private final String label;
    private final String description;
    private final String defaultPrompt;
    private final boolean cleanTranscript;

    OutputFormat(String label, String description, String defaultPrompt,
            boolean cleanTranscript) {
        this.label = label;
        this.description = description;
        this.defaultPrompt = defaultPrompt;
        this.cleanTranscript = cleanTranscript;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public String defaultPrompt() {
        return defaultPrompt;
    }

    /** True when the deliverable is the transcript itself. */
    public boolean cleanTranscript() {
        return cleanTranscript;
    }

    /** True when producing this format requires a language model. */
    public boolean usesLlm() {
        return this != VERBATIM_TRANSCRIPT && this != TRANSLATION_ONLY;
    }

    /** Short human label of the produced document (used in the editor/export). */
    public String documentLabel() {
        return switch (this) {
            case DETAILED_NOTES -> "Конспект";
            case BRIEF_NOTES -> "Краткий конспект";
            case STEP_BY_STEP -> "Инструкция";
            case CLEAN_TRANSCRIPT -> "Очищенная транскрипция";
            case METHODICAL_GUIDE -> "Методическое пособие";
            case ARTICLE -> "Статья";
            case FAQ -> "FAQ";
            case MEETING_PROTOCOL -> "Протокол встречи";
            case INTERVIEW_QA -> "Вопросы и ответы";
            case SHORT_SUMMARY -> "Краткий пересказ";
            case RECIPE -> "Рецепт";
            case DIALOGUE -> "Диалоги";
            case VERBATIM_TRANSCRIPT -> "Транскрипция";
            case TRANSLATION_ONLY -> "Перевод";
        };
    }

    /**
     * Hard structural contract for the requested deliverable. It is appended to
     * the system prompt, not to the transcript, so the model cannot mistake it
     * for lecture content and cannot silently replace the requested format with
     * the style of a different prompt template.
     */
    public String structureContract() {
        return switch (this) {
            case DETAILED_NOTES -> """
                    Формат результата: ПОДРОБНЫЙ КОНСПЕКТ.
                    Обязательные элементы: темы, определения, объяснения, примеры (только из текста), выводы.
                    Не заменяй разделы коротким пересказом и не добавляй разделы, которых нет в материале.""";
            case BRIEF_NOTES -> """
                    Формат результата: КРАТКИЙ КОНСПЕКТ.
                    Структура: краткое резюме, ключевые идеи (3–7), важные факты, выводы.
                    Никаких десятков вопросов, никаких фиктивных участников, никаких повторов.""";
            case STEP_BY_STEP -> """
                    Формат результата: ПОШАГОВАЯ ИНСТРУКЦИЯ.
                    Структура: цель, необходимые условия, последовательность шагов, команды и параметры,
                    проверка результата, типичные ошибки. Не выдумывай отсутствующие шаги.""";
            case CLEAN_TRANSCRIPT -> """
                    Формат результата: ОЧИЩЕННАЯ ТРАНСКРИПЦИЯ.
                    Близкий к оригиналу текст, абзацы и пунктуация, без произвольных сокращений,
                    пересказа и добавления новых смыслов.""";
            case METHODICAL_GUIDE -> """
                    Формат результата: МЕТОДИЧЕСКОЕ ПОСОБИЕ.
                    Структура: цели изучения, порядок изучения, ключевые понятия, задания и вопросы
                    для самопроверки — только по материалу.""";
            case ARTICLE -> """
                    Формат результата: СТАТЬЯ.
                    Связный текст: введение, тематические разделы, заключение. Без анкетных вопросов
                    и без повторяющихся блоков.""";
            case FAQ -> """
                    Формат результата: FAQ.
                    Только реальные вопросы из материала. Если вопросов мало — документ короткий.
                    Запрещено придумывать вопросы или повторять один и тот же вопрос разными словами.""";
            case MEETING_PROTOCOL -> """
                    Формат результата: ПРОТОКОЛ ВСТРЕЧИ.
                    Структура: повестка, решения, задачи и ответственные, открытые вопросы.
                    Не выдумывай участников и решения, которых не было в тексте.""";
            case INTERVIEW_QA -> """
                    Формат результата: ВОПРОСЫ И ОТВЕТЫ.
                    Только вопросы, которые действительно обсуждались. Не создавай фиктивных участников,
                    не повторяй вопросы и не добавляй раздел о разногласиях, если их нет.""";
            case SHORT_SUMMARY -> """
                    Формат результата: КРАТКИЙ ПЕРЕСКАЗ.
                    Структура: краткое содержание, основные события/идеи в порядке изложения,
                    важные детали, итог (если он есть). Без академических разделов, определений
                    и контрольных вопросов. Не переставляй события."""; 
            case RECIPE -> """
                    Формат результата: РЕЦЕПТ.
                    Структура: блюдо, ингредиенты с количествами (или «количество не указано»),
                    подготовка, последовательность приготовления, замечания автора.
                    Запрещено выдумывать ингредиенты, граммовки, температуру, время и шаги."""; 
            case DIALOGUE -> """
                    Формат результата: ДИАЛОГ.
                    Реплики в порядке произнесения, с исходными повторами и метками времени.
                    Не придумывай имена и роли говорящих. Не превращай диалог в FAQ или конспект."""; 
            case VERBATIM_TRANSCRIPT -> """
                    Формат результата: ТРАНСКРИПЦИЯ БЕЗ ИЗМЕНЕНИЙ.
                    LLM не применяется: текст распознавания сохраняется программно."""; 
            case TRANSLATION_ONLY -> """
                    Формат результата: ТОЛЬКО ПЕРЕВОД.
                    Документ собирается из переведённых сегментов без генерации конспекта.""";
        };
    }

    public static OutputFormat from(String value) {
        if (value == null || value.isBlank()) {
            return DETAILED_NOTES;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DETAILED_NOTES;
        }
    }
}
