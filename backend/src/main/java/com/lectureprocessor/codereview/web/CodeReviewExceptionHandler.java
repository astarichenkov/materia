package com.lectureprocessor.codereview.web;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.lectureprocessor.gitea.GiteaApiException;

/**
 * Maps code-review domain failures to clean HTTP responses. Gitea category is
 * translated to the closest HTTP status; messages never contain the API token.
 */
@RestControllerAdvice(assignableTypes = CodeReviewApiController.class)
public class CodeReviewExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewExceptionHandler.class);

    @ExceptionHandler(GiteaApiException.class)
    public ResponseEntity<Map<String, Object>> gitea(GiteaApiException e) {
        HttpStatus status = switch (e.category()) {
            case AUTHENTICATION, CONFIGURATION -> HttpStatus.SERVICE_UNAVAILABLE;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE, INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
        log.warn("Code review Gitea error ({}): {}", e.category(), e.getMessage());
        return response(status, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        HttpStatus status = e instanceof IllegalStateException ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.BAD_REQUEST;
        return response(status, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message));
    }
}
