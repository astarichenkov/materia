package com.lectureprocessor.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lectureprocessor.discovery.DiscoveredVideo;
import com.lectureprocessor.discovery.LocalSimilarityService;
import com.lectureprocessor.discovery.SimilarLectureView;
import com.lectureprocessor.discovery.VideoDiscoveryService;

/** Smart video discovery API: local similarity, online providers, import. */
@RestController
@RequestMapping("/api/discovery")
public class DiscoveryApiController {

    private final LocalSimilarityService similarities;
    private final VideoDiscoveryService discovery;

    public DiscoveryApiController(LocalSimilarityService similarities,
            VideoDiscoveryService discovery) {
        this.similarities = similarities;
        this.discovery = discovery;
    }

    @GetMapping("/providers")
    public List<VideoDiscoveryService.ProviderStatus> providers() {
        return discovery.providerStatuses();
    }

    @GetMapping("/similar/{lectureId}")
    public List<SimilarLectureView> similar(@PathVariable String lectureId) {
        return similarities.similar(lectureId);
    }

    @PostMapping("/search")
    public VideoDiscoveryService.DiscoverySearchResponse search(
            @RequestBody VideoDiscoveryService.DiscoverySearchRequest request) {
        return discovery.search(request);
    }

    @PostMapping("/import")
    public VideoDiscoveryService.ImportResult importVideo(
            @RequestBody VideoDiscoveryService.ImportRequest request) {
        return discovery.importVideo(request);
    }

    @GetMapping("/recent")
    public List<DiscoveredVideo> recent() {
        return discovery.recent();
    }
}
