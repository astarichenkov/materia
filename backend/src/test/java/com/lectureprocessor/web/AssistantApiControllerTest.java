package com.lectureprocessor.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lectureprocessor.assistant.AssistantAnswer;
import com.lectureprocessor.assistant.AssistantCitation;
import com.lectureprocessor.assistant.AssistantToolCall;
import com.lectureprocessor.assistant.AssistantUnavailableException;
import com.lectureprocessor.assistant.DeveloperAssistantService;
import com.lectureprocessor.assistant.DeveloperAssistantService.ToolView;
import com.lectureprocessor.assistant.DocumentationIndexService;
import com.lectureprocessor.assistant.DocumentationIndexStatus;

@WebMvcTest(controllers = AssistantApiController.class)
class AssistantApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeveloperAssistantService assistant;
    @MockitoBean
    private DocumentationIndexService indexService;

    @Test
    void helpReturnsAnswerWithCitationsAndToolCalls() throws Exception {
        when(assistant.ask(any(), anyString())).thenReturn(new AssistantAnswer(
                "Пайплайн состоит из стадий.",
                List.of(new AssistantCitation("docs/pipeline.md", "Этапы", 0, 0.9, "excerpt")),
                List.of(new AssistantToolCall("git_current_branch", true, "main")),
                true));

        mockMvc.perform(post("/api/assistant/help")
                        .contentType("application/json")
                        .content("{\"question\":\"/help Из чего состоит пайплайн?\",\"sessionId\":\"s1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Пайплайн состоит из стадий."))
                .andExpect(jsonPath("$.citations[0].sourcePath").value("docs/pipeline.md"))
                .andExpect(jsonPath("$.toolCalls[0].name").value("git_current_branch"))
                .andExpect(jsonPath("$.grounded").value(true));
    }

    @Test
    void unavailableModelReturns503() throws Exception {
        when(assistant.ask(any(), anyString()))
                .thenThrow(new AssistantUnavailableException("Ollama недоступна"));

        mockMvc.perform(post("/api/assistant/help")
                        .contentType("application/json")
                        .content("{\"question\":\"/help вопрос\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Ollama недоступна"));
    }

    @Test
    void blankQuestionReturns400() throws Exception {
        when(assistant.ask(any(), anyString()))
                .thenThrow(new IllegalArgumentException("Вопрос не может быть пустым"));

        mockMvc.perform(post("/api/assistant/help")
                        .contentType("application/json")
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusExposesIndexState() throws Exception {
        when(indexService.status()).thenReturn(new DocumentationIndexStatus(
                "/project", 3, 42, "2026-01-01T00:00:00Z", List.of("README.md", "docs/a.md", "docs/b.md")));

        mockMvc.perform(get("/api/assistant/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceCount").value(3))
                .andExpect(jsonPath("$.chunkCount").value(42));
    }

    @Test
    void toolsEndpointListsMcpTools() throws Exception {
        when(assistant.tools()).thenReturn(List.of(new ToolView("git_current_branch", "branch")));

        mockMvc.perform(get("/api/assistant/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("git_current_branch"));
    }
}
