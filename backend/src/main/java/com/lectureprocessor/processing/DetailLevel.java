package com.lectureprocessor.processing;

import java.util.List;
import java.util.Locale;

/** How detailed the produced document should be. */
public enum DetailLevel {
    BRIEF("Кратко", "Сжатая структура, только ключевые идеи и выводы."),
    STANDARD("Стандартно", "Сбалансированная детализация (по умолчанию)."),
    DETAILED("Подробно", "Максимум существенных деталей без выдуманного содержания.");

    private final String label;
    private final String instruction;

    DetailLevel(String label, String instruction) {
        this.label = label;
        this.instruction = instruction;
    }

    public String label() {
        return label;
    }

    /**
     * Extra instruction appended to the user message. It never overrides the
     * reliability rules of the prompt template.
     */
    public String instruction() {
        return switch (this) {
            case BRIEF -> "Степень детализации: КРАТКО. Оставь только главные идеи и выводы; "
                    + "не более 5 разделов. Не теряй числа, имена и термины.";
            case STANDARD -> "Степень детализации: СТАНДАРТНО. Покрой все существенные темы фрагмента.";
            case DETAILED -> "Степень детализации: ПОДРОБНО. Сохрани все существенные детали, "
                    + "определения, примеры и числа из фрагмента. Не добавляй того, чего нет в тексте.";
        };
    }

    public static DetailLevel from(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return STANDARD;
        }
    }

    public static List<DetailLevel> all() {
        return List.of(values());
    }
}
