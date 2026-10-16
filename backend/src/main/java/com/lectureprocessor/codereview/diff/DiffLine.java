package com.lectureprocessor.codereview.diff;

/** One line of a unified diff hunk. */
public record DiffLine(Type type, int oldLine, int newLine, String content) {

    public enum Type {
        ADDED, REMOVED, CONTEXT
    }
}
