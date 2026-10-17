package com.lectureprocessor.discovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

/** Registry of the enabled video search providers. */
@Service
public class VideoProviderRegistry {

    private final Map<String, VideoSearchProvider> providers = new LinkedHashMap<>();
    private final DiscoveryProperties properties;

    public VideoProviderRegistry(List<VideoSearchProvider> providers,
            DiscoveryProperties properties) {
        this.properties = properties;
        for (VideoSearchProvider provider : providers) {
            this.providers.put(provider.id(), provider);
        }
    }

    public List<VideoSearchProvider> enabled() {
        return properties.providers() == null ? List.copyOf(providers.values())
                : properties.providers().stream().map(providers::get)
                        .filter(p -> p != null).toList();
    }

    public List<VideoSearchProvider> all() {
        return List.copyOf(providers.values());
    }

    public Optional<VideoSearchProvider> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(providers.get(id));
    }
}
