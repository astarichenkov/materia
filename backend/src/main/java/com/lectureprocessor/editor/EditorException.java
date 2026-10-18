package com.lectureprocessor.editor;

import org.springframework.http.HttpStatus;

/**
 * Rejected editor operation. The {@code code} is part of the API contract and
 * lets the UI distinguish a stale-revision conflict from invalid input.
 */
public class EditorException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public EditorException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public static EditorException validation(String message) {
        return new EditorException("VALIDATION", message, HttpStatus.BAD_REQUEST);
    }

    public static EditorException conflict(String message) {
        return new EditorException("REVISION_CONFLICT", message, HttpStatus.CONFLICT);
    }

    public static EditorException notFound(String message) {
        return new EditorException("NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    public static EditorException forbidden(String message) {
        return new EditorException("FORBIDDEN", message, HttpStatus.FORBIDDEN);
    }

    public static EditorException unavailable(String message) {
        return new EditorException("UNAVAILABLE", message, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
