package com.lectureprocessor.fileagent.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.fileagent.FileAgentMetrics;
import com.lectureprocessor.fileagent.FileAgentService;
import com.lectureprocessor.fileagent.FileAgentTaskStatus;
import com.lectureprocessor.fileagent.FileChangeType;
import com.lectureprocessor.fileagent.FileWorkspace;
import com.lectureprocessor.fileagent.ProposalStatus;
import com.lectureprocessor.fileagent.model.FileAgentTask;
import com.lectureprocessor.fileagent.model.FileChangeProposal;
import com.lectureprocessor.fileagent.model.ProposedFileChange;
import com.lectureprocessor.web.security.AdminAccessService;

@WebMvcTest(controllers = FileAgentWebController.class)
class FileAgentWebControllerTest {

    private static final String CSRF = "csrf-test";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileAgentService service;
    @MockitoBean
    private AdminAccessService access;
    @MockitoBean
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        when(properties.security()).thenReturn(new AppProperties.Security("", "key", "LP_MASTER_KEY"));
        when(access.csrfToken(any())).thenReturn(CSRF);
        when(access.tokenConfigured()).thenReturn(false);
        when(service.list(anyInt())).thenReturn(List.of(task()));
        when(service.metrics()).thenReturn(new FileAgentMetrics(1, 0, 1, 0, 0, 0, 5, 0, 0, 0,
                0, Map.of("ollama", 1)));
        when(service.tools()).thenReturn(List.of(new FileAgentService.ToolView(
                "read_project_file", "read")));
        when(service.status()).thenReturn(Map.of("profile", "file-agent-read"));
        when(service.executions(anyString())).thenReturn(List.of());
        when(service.toolTrace(anyString())).thenReturn(List.of());
        when(service.proposals(anyString())).thenReturn(List.of(proposal()));
        when(service.changes(1L)).thenReturn(List.of(new ProposedFileChange(1L, 1L, "README.md",
                FileChangeType.MODIFY, "old", "new", "old", "new", "reason")));
        when(service.get(anyString())).thenReturn(task());
        when(service.createAndStart(any(), org.mockito.ArgumentMatchers.nullable(String.class),
                anyString())).thenReturn(task());
    }

    @Test
    void pageRendersPlanDiffAndApprovalControls() throws Exception {
        mockMvc.perform(get("/file-agent"))
                .andExpect(status().isOk())
                .andExpect(view().name("file-agent"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Autonomous File Agent")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Применить изменения")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "--- a/README.md")));
    }

    @Test
    void goalAndDiffAreEscaped() throws Exception {
        FileAgentTask xss = new FileAgentTask("t1", FileWorkspace.PROJECT, null,
                "<script>alert('x')</script>", FileAgentTaskStatus.AWAITING_APPROVAL,
                "plan", List.of("README.md"), "ollama", "qwen3:1.7b", null, null,
                1, 3, 1, 0, 4, 0, 1, 2, 1, 2, null, null, 0, null,
                Instant.now(), Instant.now(), null);
        when(service.list(anyInt())).thenReturn(List.of(xss));
        when(service.get(anyString())).thenReturn(xss);
        FileChangeProposal bad = new FileChangeProposal(1L, "t1", 1, ProposalStatus.AWAITING_APPROVAL,
                FileWorkspace.PROJECT, null, "<script>alert('x')</script>",
                "diff <script>alert('x')</script>", "ollama", "m", null, null, null, null,
                Instant.now(), null, null);
        when(service.proposals(anyString())).thenReturn(List.of(bad));

        mockMvc.perform(get("/file-agent"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "&lt;script&gt;alert")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<script>alert('x')</script>"))));
    }

    @Test
    void createWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/file-agent/tasks").param("goal", "update docs"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createWithCsrfStartsTask() throws Exception {
        mockMvc.perform(post("/file-agent/tasks")
                        .sessionAttr(AdminAccessService.CSRF_SESSION_ATTRIBUTE, CSRF)
                        .param("_csrf", CSRF)
                        .param("goal", "update docs")
                        .param("workspace", "PROJECT_WORKSPACE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file-agent?taskId=t1"));
        verify(service).createAndStart(eq(FileWorkspace.PROJECT),
                org.mockito.ArgumentMatchers.nullable(String.class), eq("update docs"));
    }

    @Test
    void approveRequiresCsrfAndCallsServer() throws Exception {
        when(service.approveAndApply(eq(1L), anyString())).thenReturn(
                new com.lectureprocessor.fileagent.FileChangeApplicationService.ApplyResult(
                        1L, ProposalStatus.COMPLETED, true, "applied", List.of()));
        mockMvc.perform(post("/file-agent/proposals/1/approve")
                        .sessionAttr(AdminAccessService.CSRF_SESSION_ATTRIBUTE, CSRF)
                        .param("_csrf", CSRF)
                        .param("taskId", "t1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file-agent?taskId=t1"));
        verify(service).approveAndApply(eq(1L), anyString());
    }

    private FileAgentTask task() {
        return new FileAgentTask("t1", FileWorkspace.PROJECT, null, "update docs",
                FileAgentTaskStatus.AWAITING_APPROVAL, "plan", List.of("README.md"), "ollama",
                "qwen3:1.7b", null, null, 1, 3, 1, 0, 4, 0, 1, 2, 1, 2, null, null, 0, null,
                Instant.now(), Instant.now(), null);
    }

    private FileChangeProposal proposal() {
        return new FileChangeProposal(1L, "t1", 1, ProposalStatus.AWAITING_APPROVAL,
                FileWorkspace.PROJECT, null, "summary", "--- a/README.md\n+++ b/README.md\n",
                "ollama", "qwen3:1.7b", null, null, null, null, Instant.now(), null, null);
    }
}
