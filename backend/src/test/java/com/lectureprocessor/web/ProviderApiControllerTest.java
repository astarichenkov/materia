package com.lectureprocessor.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lectureprocessor.llm.CloudCostService;
import com.lectureprocessor.llm.LlmModelInfo;
import com.lectureprocessor.llm.LlmProviderRegistry;
import com.lectureprocessor.llm.LlmTaskSettingsService;
import com.lectureprocessor.llm.ProviderDescriptor;
import com.lectureprocessor.llm.credential.ProviderCredentialService;
import com.lectureprocessor.llm.credential.ProviderCredentialService.CredentialStatus;
import com.lectureprocessor.web.security.AdminAccessService;

/**
 * Verifies the credential REST API: admin/CSRF protection and that the plaintext
 * API key is never returned by any response.
 */
@WebMvcTest(controllers = ProviderApiController.class)
class ProviderApiControllerTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmProviderRegistry registry;
    @MockitoBean
    private LlmTaskSettingsService taskSettings;
    @MockitoBean
    private ProviderCredentialService credentials;
    @MockitoBean
    private CloudCostService costs;

    private void stubProviders() {
        when(registry.providers()).thenReturn(List.of(
                new ProviderDescriptor("ollama", "Ollama (local)", true, false,
                        List.of(new LlmModelInfo("qwen3:1.7b", "qwen3", "2.0B", "Q4_K_M", 40960L,
                                List.of("completion"), 1L))),
                new ProviderDescriptor("deepseek", "DeepSeek (Cloud)", false, true, List.of())));
        com.lectureprocessor.support.FakeLlmProvider deepseek =
                new com.lectureprocessor.support.FakeLlmProvider();
        deepseek.setId("deepseek");
        deepseek.setCloud(true);
        when(registry.byId("deepseek")).thenReturn(java.util.Optional.of(deepseek));
    }

    @Test
    void listsProvidersWithoutCredentialMaterial() throws Exception {
        stubProviders();
        when(credentials.status("deepseek"))
                .thenReturn(new CredentialStatus("deepseek", false, null, null, null, null, true));

        mockMvc.perform(get("/api/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].id").value("deepseek"))
                .andExpect(jsonPath("$[1].credential.configured").value(false))
                .andExpect(jsonPath("$[1].apiKey").doesNotExist());
    }

    @Test
    void savingCredentialWithoutCsrfIsRejected() throws Exception {
        stubProviders();
        mockMvc.perform(post("/api/providers/deepseek/credential")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-secret\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void savingCredentialWithCsrfStoresItAndNeverReturnsIt() throws Exception {
        stubProviders();
        when(credentials.save(eq("deepseek"), eq("sk-secret")))
                .thenReturn(new CredentialStatus("deepseek", true, "…cret", Instant.now(), null, null, true));

        mockMvc.perform(post("/api/providers/deepseek/credential")
                        .sessionAttr(AdminAccessService.CSRF_SESSION_ATTRIBUTE, CSRF)
                        .param("_csrf", CSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("sk-secret"))));
    }

    @Test
    void deletingCredentialRequiresCsrfAndReturnsNoContent() throws Exception {
        stubProviders();
        mockMvc.perform(delete("/api/providers/deepseek/credential")
                        .sessionAttr(AdminAccessService.CSRF_SESSION_ATTRIBUTE, CSRF)
                        .param("_csrf", CSRF))
                .andExpect(status().isNoContent());
        verify(credentials).delete("deepseek");
    }

    @Test
    void credentialsAreRejectedForLocalProviders() throws Exception {
        stubProviders();
        mockMvc.perform(post("/api/providers/ollama/credential")
                        .sessionAttr(AdminAccessService.CSRF_SESSION_ATTRIBUTE, CSRF)
                        .param("_csrf", CSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-secret\"}"))
                .andExpect(status().isBadRequest());
    }
}
