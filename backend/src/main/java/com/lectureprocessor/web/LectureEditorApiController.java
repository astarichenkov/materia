package com.lectureprocessor.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lectureprocessor.editor.DocumentType;
import com.lectureprocessor.editor.DocumentExportService;
import com.lectureprocessor.editor.EditorAccessService;
import com.lectureprocessor.editor.EditorException;
import com.lectureprocessor.editor.EditorRequests;
import com.lectureprocessor.editor.EditorValidationException;
import com.lectureprocessor.editor.EditorView;
import com.lectureprocessor.editor.ExportVariant;
import com.lectureprocessor.editor.LectureDocumentRevision;
import com.lectureprocessor.editor.LectureEditorService;
import com.lectureprocessor.editor.LectureExport;
import com.lectureprocessor.editor.LineDiff;
import com.lectureprocessor.lecture.LectureNotFoundException;
import com.lectureprocessor.lecture.LectureService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST API for the lecture editor. Every endpoint is scoped to one lecture that
 * is verified to exist; state-changing endpoints require the editor CSRF token
 * ({@code X-Editor-Csrf} header or {@code _csrf} field).
 */
@RestController
@RequestMapping("/api/lectures/{id}/editor")
public class LectureEditorApiController {

    private final LectureEditorService editor;
    private final DocumentExportService exportService;
    private final LectureService lectures;
    private final EditorAccessService access;

    public LectureEditorApiController(LectureEditorService editor,
            DocumentExportService exportService, LectureService lectures,
            EditorAccessService access) {
        this.editor = editor;
        this.exportService = exportService;
        this.lectures = lectures;
        this.access = access;
    }

    @GetMapping
    public EditorView view(@PathVariable String id) {
        return editor.view(id);
    }

    @PostMapping("/transcript")
    public LectureDocumentRevision saveTranscript(@PathVariable String id,
            @RequestBody EditorRequests.TranscriptSave request, HttpServletRequest http) {
        access.requireCsrf(http);
        return editor.saveTranscript(id, request);
    }

    @PostMapping("/translation")
    public LectureDocumentRevision saveTranslation(@PathVariable String id,
            @RequestBody EditorRequests.TranslationSave request, HttpServletRequest http) {
        access.requireCsrf(http);
        return editor.saveTranslation(id, request);
    }

    @PostMapping("/translation/segments/{sequence}/review")
    public Map<String, Object> review(@PathVariable String id, @PathVariable int sequence,
            HttpServletRequest http) {
        access.requireCsrf(http);
        editor.markReviewed(id, sequence);
        return Map.of("sequence", sequence, "status", "REVIEWED");
    }

    @PostMapping("/translation/segments/{sequence}/retry")
    public EditorView.RetranslationPreview retry(@PathVariable String id,
            @PathVariable int sequence, HttpServletRequest http) {
        access.requireCsrf(http);
        return editor.previewRetranslation(id, sequence);
    }

    @PostMapping("/translation/segments/{sequence}/accept")
    public LectureDocumentRevision accept(@PathVariable String id, @PathVariable int sequence,
            @RequestBody EditorRequests.AcceptSuggestionRequest request, HttpServletRequest http) {
        access.requireCsrf(http);
        EditorRequests.AcceptSuggestionRequest scoped = new EditorRequests.AcceptSuggestionRequest(
                sequence, request == null ? null : request.text(),
                request == null ? null : request.provider(), request == null ? null : request.model(),
                request == null ? null : request.expectedRevision(),
                request == null ? null : request.description());
        return editor.acceptRetranslation(id, scoped);
    }

    @PostMapping("/notes")
    public LectureDocumentRevision saveNotes(@PathVariable String id,
            @RequestBody EditorRequests.NotesSave request, HttpServletRequest http) {
        access.requireCsrf(http);
        return editor.saveNotes(id, request);
    }

    @PostMapping("/notes/regenerate")
    public LectureDocumentRevision regenerateNotes(@PathVariable String id,
            HttpServletRequest http) {
        access.requireCsrf(http);
        return editor.regenerateNotes(id);
    }

    @PostMapping("/notes/preview")
    public Map<String, String> preview(@PathVariable String id,
            @RequestBody EditorRequests.MarkdownPreviewRequest request,
            HttpServletRequest http) {
        access.requireCsrf(http);
        return Map.of("html", editor.renderMarkdown(id,
                request == null ? null : request.markdown()));
    }

    @GetMapping("/revisions")
    public List<LectureDocumentRevision> revisions(@PathVariable String id,
            @RequestParam("type") String type,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return editor.history(id, DocumentType.from(type), limit);
    }

    @GetMapping("/revisions/{revisionId}")
    public LectureDocumentRevision revision(@PathVariable String id, @PathVariable long revisionId,
            @RequestParam("type") String type) {
        return editor.history(id, DocumentType.from(type), 500).stream()
                .filter(revision -> revision.id() == revisionId)
                .findFirst()
                .orElseThrow(() -> EditorException.notFound("Версия не найдена"));
    }

    @PostMapping("/revisions/{revisionId}/restore")
    public LectureDocumentRevision restore(@PathVariable String id, @PathVariable long revisionId,
            @RequestParam("type") String type, HttpServletRequest http) {
        access.requireCsrf(http);
        return editor.restore(id, DocumentType.from(type), revisionId);
    }

    @GetMapping("/compare")
    public List<LineDiff.Line> compare(@PathVariable String id, @RequestParam("type") String type,
            @RequestParam("from") long from, @RequestParam("to") long to) {
        return editor.compare(id, DocumentType.from(type), from, to);
    }

    @PostMapping("/export")
    public LectureExport export(@PathVariable String id, @RequestBody ExportRequest request,
            HttpServletRequest http) {
        access.requireCsrf(http);
        if (request == null || request.variant() == null) {
            throw EditorException.validation("Не задан вариант экспорта");
        }
        return exportService.export(id, ExportVariant.from(request.variant()));
    }

    @GetMapping("/exports")
    public List<LectureExport> exports(@PathVariable String id) {
        lectures.getRequired(id);
        return editor.view(id).exportHistory();
    }

    @PutMapping("/settings/cloud-retry")
    public Map<String, Object> cloudRetry(@PathVariable String id,
            @RequestBody EditorRequests.CloudRetrySetting request, HttpServletRequest http) {
        access.requireCsrf(http);
        lectures.getRequired(id);
        boolean enabled = request != null && Boolean.TRUE.equals(request.enabled());
        editor.setAllowCloudRetry(enabled);
        return Map.of("allowCloudRetry", editor.allowCloudRetry(),
                "cloudRetryConfigured", editor.cloudRetryConfigured());
    }

    @ExceptionHandler(EditorValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(EditorValidationException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage(), "code", "VALIDATION"));
    }

    @ExceptionHandler(EditorException.class)
    public ResponseEntity<Map<String, Object>> handleEditor(EditorException e) {
        return ResponseEntity.status(e.status()).body(Map.of(
                "error", e.getMessage(), "code", e.code()));
    }

    @ExceptionHandler(LectureNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleMissing(LectureNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "Лекция не найдена", "code", "NOT_FOUND"));
    }

    /** Request body for a re-export. */
    public record ExportRequest(String variant) {
    }
}
