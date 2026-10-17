package com.lectureprocessor.fileagent.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.lectureprocessor.fileagent.FileAgentException;

/** Maps file-agent failures to bounded JSON errors without stack traces. */
@RestControllerAdvice(assignableTypes = FileAgentApiController.class)
public class FileAgentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FileAgentExceptionHandler.class);

    @ExceptionHandler(FileAgentException.class)
    public ResponseEntity<Map<String, String>> handle(FileAgentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(RuntimeException e) {
        log.warn("File agent API error: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Внутренняя ошибка File Agent"));
    }
}
