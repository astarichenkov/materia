package com.lectureprocessor.web;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.lectureprocessor.document.DocumentExportException;
import com.lectureprocessor.download.MediaDownloadException;
import com.lectureprocessor.download.UnsafeMediaUrlException;
import com.lectureprocessor.job.JobQueueFullException;
import com.lectureprocessor.lecture.InvalidUploadException;
import com.lectureprocessor.lecture.LectureNotFoundException;
import com.lectureprocessor.llm.LlmException;
import com.lectureprocessor.process.ProcessExecutionException;
import com.lectureprocessor.text.ProcessingException;
import com.lectureprocessor.transcription.TranscriptionException;

/**
 * Translates domain exceptions into clean HTTP responses without leaking stack
 * traces or internal details to clients.
 */
@RestControllerAdvice(assignableTypes = LectureApiController.class)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(LectureNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(LectureNotFoundException e) {
        return response(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidUploadException.class)
    public ResponseEntity<Map<String, Object>> badUpload(InvalidUploadException e) {
        return response(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return response(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(UnsafeMediaUrlException.class)
    public ResponseEntity<Map<String, Object>> unsafeUrl(UnsafeMediaUrlException e) {
        return response(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MediaDownloadException.class)
    public ResponseEntity<Map<String, Object>> downloadError(MediaDownloadException e) {
        log.warn("Download error ({}): {}", e.reason(), e.getMessage());
        if (e.detail() != null && !e.detail().isBlank()) {
            log.debug("yt-dlp detail: {}", e.detail());
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.BAD_GATEWAY.value());
        body.put("error", HttpStatus.BAD_GATEWAY.getReasonPhrase());
        body.put("message", e.getMessage() == null ? "" : e.getMessage());
        body.put("retryable", e.retryable());
        if (e.reason() != null) {
            body.put("reason", e.reason());
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(JobQueueFullException.class)
    public ResponseEntity<Map<String, Object>> queueFull(JobQueueFullException e) {
        return response(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler({ProcessExecutionException.class, TranscriptionException.class,
            LlmException.class, ProcessingException.class, DocumentExportException.class})
    public ResponseEntity<Map<String, Object>> processingError(RuntimeException e) {
        log.error("Processing error: {}", e.getMessage());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message));
    }
}
