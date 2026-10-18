package com.lectureprocessor.editor;

/** Thrown when a request body is syntactically or semantically invalid. */
public class EditorValidationException extends RuntimeException {

    public EditorValidationException(String message) {
        super(message);
    }
}
