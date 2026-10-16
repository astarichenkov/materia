package com.lectureprocessor.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.lectureprocessor.assistant.DeveloperAssistantService;
import com.lectureprocessor.assistant.DocumentationIndexService;

/**
 * Thymeleaf UI for the developer assistant. The {@code /help} command is
 * submitted with HTMX and rendered as a fragment.
 */
@Controller
public class AssistantWebController {

    private final DeveloperAssistantService assistant;
    private final DocumentationIndexService indexService;

    public AssistantWebController(DeveloperAssistantService assistant,
            DocumentationIndexService indexService) {
        this.assistant = assistant;
        this.indexService = indexService;
    }

    @GetMapping("/assistant")
    public String page(Model model) {
        model.addAttribute("status", indexService.status());
        model.addAttribute("tools", assistant.tools());
        return "assistant";
    }

    @PostMapping("/assistant/reindex")
    public String reindex(RedirectAttributes redirect) {
        try {
            var result = indexService.index();
            redirect.addFlashAttribute("message",
                    "Документация переиндексирована: документов " + result.documents()
                            + ", фрагментов " + result.chunks());
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", "Индексация не удалась: " + e.getMessage());
        }
        return "redirect:/assistant";
    }

    @PostMapping("/assistant/help")
    public String help(@RequestParam("question") String question,
            @RequestParam(name = "session", required = false) String session, Model model) {
        model.addAttribute("question", question);
        try {
            model.addAttribute("answer", assistant.ask(session, question));
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "fragments/assistant-answer";
    }
}
