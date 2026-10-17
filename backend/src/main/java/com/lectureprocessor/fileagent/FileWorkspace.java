package com.lectureprocessor.fileagent;

import java.util.Locale;

/**
 * Explicit file workspace for the Day 34 file agent. Workspaces are isolated:
 * a task may read/propose only inside the workspace it was created for.
 */
public enum FileWorkspace {

    /** Repository sources, tests, README and docs. */
    PROJECT("PROJECT_WORKSPACE", "Проект"),

    /** One lecture artifact directory (transcript, notes, generated docs). */
    LECTURE("LECTURE_WORKSPACE", "Лекция");

    private final String label;
    private final String displayName;

    FileWorkspace(String label, String displayName) {
        this.label = label;
        this.displayName = displayName;
    }

    public String label() {
        return label;
    }

    public String displayName() {
        return displayName;
    }

    public static FileWorkspace from(String value) {
        if (value == null || value.isBlank()) {
            throw new FileAgentException("Не задан workspace файлового агента");
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (FileWorkspace workspace : values()) {
            if (workspace.name().equals(normalized) || workspace.label.equals(normalized)) {
                return workspace;
            }
        }
        throw new FileAgentException("Неизвестный workspace: " + value);
    }
}
