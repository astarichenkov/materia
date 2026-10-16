package com.lectureprocessor.support.web;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.lectureprocessor.support.SupportAccessDeniedException;
import com.lectureprocessor.support.SupportRequestException;
import com.lectureprocessor.support.SupportUnavailableException;

/** Clean HTTP responses for the support API without leaking stack traces. */
@RestControllerAdvice(assignableTypes = SupportApiController.class)
public class SupportExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SupportExceptionHandler.class);

    @ExceptionHandler(SupportAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> forbidden(SupportAccessDeniedException e) {
        return response(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(SupportRequestException.class)
    public ResponseEntity<Map<String, Object>> badRequest(SupportRequestException e) {
        return response(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(SupportUnavailableException.class)
    public ResponseEntity<Map<String, Object>> unavailable(SupportUnavailableException e) {
        log.warn("Support assistant unavailable: {}", e.getMessage());
        return response(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegal(IllegalArgumentException e) {
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
