package com.lectureprocessor.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.editor.DocumentType;
import com.lectureprocessor.editor.EditorAccessService;
import com.lectureprocessor.editor.EditorException;
import com.lectureprocessor.editor.EditorRequests;
import com.lectureprocessor.editor.EditorView;
import com.lectureprocessor.editor.LectureDocumentRevision;
import com.lectureprocessor.editor.EditSource;
import com.lectureprocessor.editor.ExportVariant;
import com.lectureprocessor.editor.LectureEditorService;
import com.lectureprocessor.editor.LectureExport;
import com.lectureprocessor.lecture.LectureNotFoundException;
import com.lectureprocessor.lecture.LectureService;

import jakarta.servlet.http.HttpServletRequest;

@WebMvcTest(controllers = LectureEditorApiController.class)
class LectureEditorApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LectureEditorService editor;
    @MockitoBean
    private com.lectureprocessor.editor.DocumentExportService exportService;
    @MockitoBean
    private LectureService lectures;
    @MockitoBean
    private EditorAccessService access;

    private final Lecture lecture = new Lecture("l1", "lecture.mp4", "source.mp4", 100,
            "video/mp4", JobStatus.COMPLETED, null, 1000L, false, true, null, null, null, null,
            Instant.now(), Instant.now());

    @BeforeEach
    void allowCsrf() {
        doNothing().when(access).requireCsrf(any(HttpServletRequest.class));
        when(lectures.getRequired("l1")).thenReturn(lecture);
    }

    private EditorView view() {
        return new EditorView(lecture, "en", "en", "TRANSLATE_TO_RUSSIAN", "ru", List.of(),
                new EditorView.TranslationView(null, null, null, null, null, null, List.of()),
                "# notes", Map.of(), Map.of(), List.of(),
                new EditorView.QualitySummary(0, 0, 0, 0, 0, 0, 0), List.of(), List.of(),
                false, false);
    }

    private LectureDocumentRevision revision() {
        return new LectureDocumentRevision(1, "l1", DocumentType.TRANSCRIPT, 2, "[]", "hash", 1L,
                EditSource.HUMAN, null, Instant.now());
    }

    @Test
    void viewIsScopedToLecture() throws Exception {
        when(editor.view("l1")).thenReturn(view());
        mockMvc.perform(get("/api/lectures/l1/editor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lecture.id").value("l1"));
    }

    @Test
    void saveTranscriptReturnsRevision() throws Exception {
        when(editor.saveTranscript(eq("l1"), any(EditorRequests.TranscriptSave.class)))
                .thenReturn(revision());
        mockMvc.perform(post("/api/lectures/l1/editor/transcript")
                        .contentType("application/json")
                        .content("{\"expectedRevision\":1,\"edits\":[{\"sequence\":0,\"text\":\"x\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(2));
    }

    @Test
    void csrfFailureIsForbidden() throws Exception {
        doThrow(EditorException.forbidden("bad csrf")).when(access)
                .requireCsrf(any(HttpServletRequest.class));
        mockMvc.perform(post("/api/lectures/l1/editor/transcript")
                        .contentType("application/json")
                        .content("{\"edits\":[{\"sequence\":0,\"text\":\"x\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void conflictIsReportedAs409() throws Exception {
        when(editor.saveTranscript(eq("l1"), any()))
                .thenThrow(EditorException.conflict("stale"));
        mockMvc.perform(post("/api/lectures/l1/editor/transcript")
                        .contentType("application/json")
                        .content("{\"expectedRevision\":1,\"edits\":[{\"sequence\":0,\"text\":\"x\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
    }

    @Test
    void unknownLectureReturns404() throws Exception {
        when(editor.view("missing")).thenThrow(new LectureNotFoundException("missing"));
        mockMvc.perform(get("/api/lectures/missing/editor"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void exportRequiresVariant() throws Exception {
        mockMvc.perform(post("/api/lectures/l1/editor/export")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void exportDelegatesToService() throws Exception {
        when(exportService.export("l1", ExportVariant.RUSSIAN)).thenReturn(new LectureExport(1,
                "l1", ExportVariant.RUSSIAN, "notes.ru.docx", "hash", 1L, 2L, null, 100L,
                "COMPLETED", null, Instant.now()));
        mockMvc.perform(post("/api/lectures/l1/editor/export")
                        .contentType("application/json").content("{\"variant\":\"RUSSIAN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact").value("notes.ru.docx"));
    }

    @Test
    void revisionsRequireKnownType() throws Exception {
        mockMvc.perform(get("/api/lectures/l1/editor/revisions").param("type", "UNKNOWN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void imageOrForeignArtifactsAreNotAddressableFromEditorApi() throws Exception {
        // The editor API only exposes lecture-scoped editor resources; there is no
        // endpoint that accepts a filesystem path.
        mockMvc.perform(get("/api/lectures/l1/editor/download").param("path", "C:/Windows/win.ini"))
                .andExpect(status().isNotFound());
    }
}
