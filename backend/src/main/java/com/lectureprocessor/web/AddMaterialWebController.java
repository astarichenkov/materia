package com.lectureprocessor.web;

import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.lectureprocessor.download.MediaQuality;
import com.lectureprocessor.importing.ImportCapabilitiesService;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmSettingsService;
import com.lectureprocessor.multilingual.MultilingualProperties;

/**
 * The single entry point for adding material (UX-4 import wizard).
 *
 * <p>The page only renders state. Every submission still goes through the
 * existing endpoints ({@code POST /lectures}, {@code POST /lectures/from-url},
 * {@code GET /lectures/preview}, {@code POST /discover/import}); no download,
 * lecture creation or pipeline logic is duplicated here.</p>
 */
@Controller
public class AddMaterialWebController {

    private final ImportCapabilitiesService capabilities;
    private final MultilingualProperties multilingual;
    private final LlmSettingsService llmSettings;

    public AddMaterialWebController(ImportCapabilitiesService capabilities,
            MultilingualProperties multilingual, LlmSettingsService llmSettings) {
        this.capabilities = capabilities;
        this.multilingual = multilingual;
        this.llmSettings = llmSettings;
    }

    @GetMapping("/add")
    public String add(@RequestParam(name = "url", required = false) String url, Model model) {
        String candidate = url == null ? "" : url.strip();
        // The quick-link from the dashboard carries the URL; an over-long value
        // is not echoed back into the form or the redirect URL.
        if (candidate.length() > 2048) {
            candidate = "";
        }
        model.addAttribute("url", candidate);
        model.addAttribute("requestId", UUID.randomUUID().toString());
        var caps = capabilities.capabilities();
        model.addAttribute("capabilities", caps);
        model.addAttribute("acceptAttr", String.join(",", caps.fileExtensions()));
        model.addAttribute("platformsLabel", String.join(", ", caps.platforms()));
        model.addAttribute("defaultLanguageMode", multilingual.defaultLanguageMode());
        model.addAttribute("defaultTranslationMode", multilingual.defaultTranslationMode());
        model.addAttribute("autoTranslateDefault", multilingual.autoTranslate());
        model.addAttribute("qualities", MediaQuality.values());
        model.addAttribute("modelLabel", modelLabel());
        return "add";
    }

    /**
     * Read-only description of the LLM used for new jobs. The wizard never
     * changes global configuration; per-job model selection does not exist.
     */
    private String modelLabel() {
        try {
            LlmRunConfig config = llmSettings.current();
            String provider = config.provider() == null ? "ollama" : config.provider();
            String model = config.model() == null ? "" : config.model();
            return model.isBlank() ? provider : provider + " / " + model;
        } catch (RuntimeException e) {
            return "не определена — откройте настройки";
        }
    }
}
