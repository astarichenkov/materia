package com.lectureprocessor.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Controller;

import tools.jackson.databind.ObjectMapper;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.lectureprocessor.editor.DocumentType;
import com.lectureprocessor.editor.EditorAccessService;
import com.lectureprocessor.editor.EditorView;
import com.lectureprocessor.editor.LectureEditorService;
import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.processing.ProcessingProfile;
import com.lectureprocessor.processing.ProcessingProfileService;

import jakarta.servlet.http.HttpSession;

/** Thymeleaf page for the lecture editor. */
@Controller
public class LectureEditorWebController {

    private final LectureEditorService editor;
    private final LectureService lectures;
    private final EditorAccessService access;
    private final ObjectMapper objectMapper;
    private final ProcessingProfileService profiles;

    public LectureEditorWebController(LectureEditorService editor, LectureService lectures,
            EditorAccessService access, ObjectMapper objectMapper,
            ProcessingProfileService profiles) {
        this.editor = editor;
        this.lectures = lectures;
        this.access = access;
        this.objectMapper = objectMapper;
        this.profiles = profiles;
    }

    @GetMapping("/lectures/{id}/editor")
    public String editor(@PathVariable String id, Model model, HttpSession session) {
        EditorView view = editor.view(id);
        model.addAttribute("lecture", lectures.getRequired(id));
        model.addAttribute("view", view);
        model.addAttribute("csrf", access.csrfToken(session));
        ProcessingProfile profile = profiles.get(id);
        model.addAttribute("documentLabel", profile.documentLabel());
        model.addAttribute("scenarioTitle",
                profile.scenario() == null ? null : profile.scenario().title());
        Map<String, Integer> revisionNumbers = new LinkedHashMap<>();
        for (DocumentType type : DocumentType.values()) {
            revisionNumbers.put(type.name(), editor.latestRevisionNumber(id, type));
        }
        model.addAttribute("revisionNumbers", revisionNumbers);
        Map<String, String> stateStatus = new LinkedHashMap<>();
        Map<String, String> stateReason = new LinkedHashMap<>();
        for (DocumentType type : DocumentType.values()) {
            var state = view.documentStates().get(type);
            stateStatus.put(type.name(), state == null ? "CURRENT" : state.status().name());
            stateReason.put(type.name(), state == null ? null : state.reason());
        }
        model.addAttribute("stateStatus", stateStatus);
        model.addAttribute("stateReason", stateReason);
        // Serialized for the editor JS; `<` is escaped so a document can never
        // break out of the JSON script block.
        String viewJson = objectMapper.writeValueAsString(view).replace("<", "\\u003c");
        model.addAttribute("viewJson", viewJson);
        return "editor";
    }
}
