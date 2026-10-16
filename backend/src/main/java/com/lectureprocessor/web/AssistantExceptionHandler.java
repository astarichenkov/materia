package com.lectureprocessor.web;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.lectureprocessor.assistant.AssistantBusyException;
import com.lectureprocessor.assistant.AssistantUnavailableException;

/** Clean HTTP responses for the assistant API without leaking stack traces. */
@RestControllerAdvice(assignableTypes = {AssistantApiController.class, McpController.class})
public class AssistantExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AssistantExceptionHandler.class);

    @ExceptionHandler(AssistantUnavailableException.class)
    public ResponseEntity<Map<String, Object>> unavailable(AssistantUnavailableException e) {
        log.warn("Assistant unavailable: {}", e.getMessage());
        return response(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(AssistantBusyException.class)
    public ResponseEntity<Map<String, Object>> busy(AssistantBusyException e) {
        return response(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return response(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message));
    }
}
