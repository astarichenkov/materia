package com.lectureprocessor.processing;

import java.util.Locale;

/**
 * Kind of source material. It changes how the same transcript is structured:
 * a practical tutorial is a sequence of actions, an interview is questions and
 * answers. Values are persisted by name and must stay stable.
 */
public enum MaterialType {
    AUTO("Определить автоматически",
            "Модель выбирает структуру по содержанию транскрипции."),
    ACADEMIC_LECTURE("Академическая лекция",
            "Определения, классификации, аргументы, примеры и выводы."),
    PRACTICAL_TUTORIAL("Практический видеоурок",
            "Пошаговые действия, команды, параметры и ожидаемый результат."),
    PRESENTATION("Презентация",
            "Тезисы по слайдам, ключевые числа и выводы."),
    WEBINAR("Вебинар",
            "Темы, ответы на вопросы и практические рекомендации."),
    INTERVIEW("Интервью",
            "Вопросы, ответы и позиции участников без выдуманной атрибуции."),
    TECHNICAL_DEMO("Техническая демонстрация",
            "Команды, конфигурация, ограничения и наблюдаемый результат."),
    DISCUSSION("Дискуссия",
            "Позиции сторон, аргументы, разногласия и итоги.");

    private final String label;
    private final String description;

    MaterialType(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static MaterialType from(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AUTO;
        }
    }
}
