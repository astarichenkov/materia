package com.lectureprocessor.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.TestProperties;

class LlmProviderRegistryTest {

    private static LlmModelInfo chat(String name, String quant) {
        return new LlmModelInfo(name, "qwen3", "2.0B", quant, 40960L,
                List.of("completion", "thinking"), 1_000L);
    }

    private static LlmModelInfo embedding(String name) {
        return new LlmModelInfo(name, "nomic-bert", "137M", "F16", 2048L, List.of("embedding"), 300L);
    }

    @Test
    void listsLocalAndCloudProviders() {
        FakeLlmProvider fake = new FakeLlmProvider();
        fake.setModels(List.of(chat("qwen3:1.7b", "Q4_K_M"), embedding("nomic-embed-text")));
        LlmProviderRegistry registry = new LlmProviderRegistry(List.of(fake),
                TestProperties.defaults("./target/test-data"));

        List<ProviderDescriptor> providers = registry.providers();
        assertThat(providers).extracting(ProviderDescriptor::id).contains("ollama", "openai");

        ProviderDescriptor ollama = providers.stream().filter(p -> p.id().equals("ollama")).findFirst().orElseThrow();
        assertThat(ollama.cloud()).isFalse();
        assertThat(ollama.models()).hasSize(2);

        ProviderDescriptor openai = providers.stream().filter(p -> p.id().equals("openai")).findFirst().orElseThrow();
        assertThat(openai.cloud()).isTrue();
        assertThat(openai.available()).isFalse();
    }

    @Test
    void chatModelsExcludeEmbeddingModels() {
        FakeLlmProvider fake = new FakeLlmProvider();
        fake.setModels(List.of(chat("qwen3:1.7b", "Q4_K_M"), embedding("nomic-embed-text")));
        LlmProviderRegistry registry = new LlmProviderRegistry(List.of(fake),
                TestProperties.defaults("./target/test-data"));

        assertThat(registry.chatModels("ollama")).extracting(LlmModelInfo::name)
                .containsExactly("qwen3:1.7b");
    }

    @Test
    void unknownProviderIsRejected() {
        FakeLlmProvider fake = new FakeLlmProvider();
        LlmProviderRegistry registry = new LlmProviderRegistry(List.of(fake),
                TestProperties.defaults("./target/test-data"));

        assertThat(registry.byId("openai")).isEmpty();
        assertThatThrownBy(() -> registry.require("openai"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
