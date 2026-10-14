package com.lectureprocessor.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;

/**
 * Discovers the LLM providers available in this deployment. Local providers are
 * backed by a real {@link LlmProvider} bean; cloud providers are described by
 * configuration only (no cloud implementation is shipped yet), so they appear in
 * the UI as unavailable instead of being silently missing.
 */
@Service
public class LlmProviderRegistry {

    private final Map<String, LlmProvider> beans = new LinkedHashMap<>();
    private final AppProperties properties;

    public LlmProviderRegistry(List<LlmProvider> providers, AppProperties properties) {
        this.properties = properties;
        for (LlmProvider provider : providers) {
            beans.put(provider.id(), provider);
        }
    }

    public List<ProviderDescriptor> providers() {
        List<ProviderDescriptor> descriptors = new ArrayList<>();
        Map<String, AppProperties.Provider> configured =
                properties.llm() == null || properties.llm().providers() == null
                        ? Map.of() : properties.llm().providers();

        for (Map.Entry<String, AppProperties.Provider> entry : configured.entrySet()) {
            String id = entry.getKey();
            AppProperties.Provider settings = entry.getValue();
            LlmProvider bean = beans.get(id);
            if (bean != null) {
                boolean available = settings.enabled() && bean.isAvailable();
                descriptors.add(new ProviderDescriptor(id,
                        settings.displayName() == null ? bean.displayName() : settings.displayName(),
                        available, bean.cloud(), bean.listModels()));
            } else {
                descriptors.add(new ProviderDescriptor(id,
                        settings.displayName() == null ? id : settings.displayName(),
                        false, true, List.of()));
            }
        }
        // Providers present as beans but absent from configuration are still listed.
        for (Map.Entry<String, LlmProvider> entry : beans.entrySet()) {
            if (descriptors.stream().noneMatch(d -> d.id().equals(entry.getKey()))) {
                LlmProvider bean = entry.getValue();
                descriptors.add(new ProviderDescriptor(bean.id(), bean.displayName(),
                        bean.isAvailable(), bean.cloud(), bean.listModels()));
            }
        }
        return descriptors;
    }

    public Optional<LlmProvider> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        LlmProvider provider = beans.get(id);
        if (provider != null) {
            return Optional.of(provider);
        }
        // Unknown/cloud providers are not backed by a bean.
        return Optional.empty();
    }

    public LlmProvider require(String id) {
        return byId(id).orElseThrow(() -> new IllegalArgumentException(
                "LLM provider недоступен: " + (id == null ? "(не задан)" : id)));
    }

    public List<LlmModelInfo> chatModels(String providerId) {
        return byId(providerId).map(LlmProvider::listModels).orElse(List.of()).stream()
                .filter(LlmModelInfo::chatCapable)
                .toList();
    }
}
