package com.lectureprocessor.fileagent.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.fileagent.FileAgentMetrics;
import com.lectureprocessor.fileagent.FileAgentService;
import com.lectureprocessor.fileagent.FileAgentTaskStatus;
import com.lectureprocessor.fileagent.FileWorkspace;
import com.lectureprocessor.fileagent.ProposalStatus;
import com.lectureprocessor.fileagent.model.FileAgentTask;
import com.lectureprocessor.fileagent.model.FileChangeProposal;
import com.lectureprocessor.web.security.AdminAccessService;

@WebMvcTest(controllers = FileAgentApiController.class)
class FileAgentApiControllerTest {

    private static final String CSRF = "csrf-api";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileAgentService service;
    @MockitoBean
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        when(properties.security()).thenReturn(new AppProperties.Security("", "key", "LP_MASTER_KEY"));
        when(service.createAndStart(any(), any(), anyString())).thenReturn(task());
        when(service.get(anyString())).thenReturn(task());
        when(service.metrics()).thenReturn(new FileAgentMetrics(1, 0, 1, 0, 0, 0, 2, 0, 0, 0,
                0, Map.of()));
    }

    @Test
    void createTaskWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/api/file-agent/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"update docs\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createTaskWithCsrfReturnsTask() throws Exception {
        mockMvc.perform(post("/api/file-agent/tasks")
                        .sessionAttr(AdminAccessService.CSRF_SESSION_ATTRIBUTE, CSRF)
                        .param("_csrf", CSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"update docs\",\"workspace\":\"PROJECT_WORKSPACE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t1"))
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));
    }

    @Test
    void rejectsProposalWithCsrf() throws Exception {
        mockMvc.perform(post("/api/file-agent/proposals/1/reject")
                        .sessionAttr(AdminAccessService.CSRF_SESSION_ATTRIBUTE, CSRF)
                        .param("_csrf", CSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"no\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void approveProposalReturnsAppliedStatus() throws Exception {
        when(service.approveAndApply(eq(1L), anyString())).thenReturn(
                new com.lectureprocessor.fileagent.FileChangeApplicationService.ApplyResult(
                        1L, ProposalStatus.COMPLETED, true, "applied", List.of("README.md")));
        mockMvc.perform(post("/api/file-agent/proposals/1/approve")
                        .sessionAttr(AdminAccessService.CSRF_SESSION_ATTRIBUTE, CSRF)
                        .param("_csrf", CSRF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void metricsAreExposed() throws Exception {
        mockMvc.perform(get("/api/file-agent/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTasks").value(1));
    }

    @Test
    void proposalDiffIsPlainText() throws Exception {
        when(service.proposal(1L)).thenReturn(new FileChangeProposal(1L, "t1", 1,
                ProposalStatus.AWAITING_APPROVAL, FileWorkspace.PROJECT, null, "summary",
                "--- a/README.md\n", "ollama", "m", null, null, null, null, Instant.now(),
                null, null));
        mockMvc.perform(get("/api/file-agent/proposals/1/diff"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("--- a/README.md")));
    }

    private FileAgentTask task() {
        return new FileAgentTask("t1", FileWorkspace.PROJECT, null, "update docs",
                FileAgentTaskStatus.AWAITING_APPROVAL, "plan", List.of("README.md"), "ollama",
                "qwen3:1.7b", null, null, 1, 3, 1, 0, 4, 0, 1, 2, 1, 2, null, null, 0, null,
                Instant.now(), Instant.now(), null);
    }
}
