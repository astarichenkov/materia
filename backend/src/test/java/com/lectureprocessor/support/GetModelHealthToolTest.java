package com.lectureprocessor.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.llm.LlmModelInfo;
import com.lectureprocessor.llm.LlmProviderRegistry;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmTaskSettingsService;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.credential.ProviderCredentialService;
import com.lectureprocessor.llm.credential.ProviderCredentialService.CredentialStatus;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.TestProperties;
import com.lectureprocessor.support.tool.GetModelHealthTool;

import tools.jackson.databind.ObjectMapper;

class GetModelHealthToolTest {

    @Test
    void reportsSelectedProviderAndLastCredentialStatusWithoutSecret() {
        FakeLlmProvider ollama = new FakeLlmProvider();
        ollama.setModels(List.of(new LlmModelInfo("qwen3:1.7b", "qwen3", "2B", "Q4", 40960L,
                List.of("completion"), 1_000_000L)));
        FakeLlmProvider deepseek = new FakeLlmProvider();
        deepseek.setId("deepseek");
        deepseek.setCloud(true);
        deepseek.setAvailable(false);
        LlmProviderRegistry registry = new LlmProviderRegistry(List.of(ollama, deepseek),
                TestProperties.defaults("./target/test-data"));

        LlmTaskSettingsService taskSettings = mock(LlmTaskSettingsService.class);
        when(taskSettings.configFor(LlmTaskType.SUPPORT_ASSISTANT))
                .thenReturn(new LlmRunConfig("deepseek", "deepseek-flash", 0.1, 1536, 8192, false, "p"));
        ProviderCredentialService credentials = mock(ProviderCredentialService.class);
        when(credentials.status("deepseek")).thenReturn(new CredentialStatus("deepseek", true, "abcd",
                Instant.now(), Instant.now().minusSeconds(60), "api_key=leakedvalue", false));

        GetModelHealthTool tool = new GetModelHealthTool(registry, taskSettings, credentials,
                new ObjectMapper());
        var result = tool.invoke(Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("\"provider\":\"deepseek\"")
                .contains("\"model\":\"deepseek-flash\"")
                .contains("\"configured\":true");
        assertThat(result.text()).doesNotContain("leakedvalue");
        assertThat(result.text()).contains("[REDACTED]");
    }
}
