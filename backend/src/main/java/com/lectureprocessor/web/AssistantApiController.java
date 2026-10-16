package com.lectureprocessor.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lectureprocessor.assistant.AssistantAnswer;
import com.lectureprocessor.assistant.DeveloperAssistantService;
import com.lectureprocessor.assistant.DeveloperAssistantService.ToolView;
import com.lectureprocessor.assistant.DocumentationIndexService;
import com.lectureprocessor.assistant.DocumentationIndexStatus;
import com.lectureprocessor.assistant.IndexResult;

/**
 * REST API for the Day 31 developer assistant: {@code /help}, documentation
 * index status/rebuild and the read-only MCP tool catalog.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantApiController {

    private final DeveloperAssistantService assistant;
    private final DocumentationIndexService indexService;

    public AssistantApiController(DeveloperAssistantService assistant,
            DocumentationIndexService indexService) {
        this.assistant = assistant;
        this.indexService = indexService;
    }

    @PostMapping("/help")
    public AssistantAnswer help(@RequestBody AssistantHelpRequest request) {
        return assistant.ask(request.sessionId(), request.question());
    }

    @PostMapping("/clear")
    public void clear(@RequestBody AssistantHelpRequest request) {
        assistant.clear(request.sessionId());
    }

    @GetMapping("/status")
    public DocumentationIndexStatus status() {
        return indexService.status();
    }

    @PostMapping("/index")
    public IndexResult index() {
        return indexService.index();
    }

    @GetMapping("/tools")
    public List<ToolView> tools() {
        return assistant.tools();
    }
}
