package com.lectureprocessor.scenario;

/** Grouping used by the scenario picker in the UI. */
public enum ScenarioCategory {
    UNDERSTANDING("Понять содержание"),
    STRUCTURED("Получить структуру"),
    TRANSCRIPT("Работа с текстом"),
    LANGUAGE("Язык");

    private final String label;

    ScenarioCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
