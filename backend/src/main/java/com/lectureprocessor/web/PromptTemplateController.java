package com.lectureprocessor.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.lectureprocessor.scenario.ScenarioCatalog;
import com.lectureprocessor.text.PromptTemplateService;
import com.lectureprocessor.web.security.AdminAccessService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Prompt template studio.
 *
 * <p>Built-in templates are read-only. Users can create a copy, edit it, restore
 * the source version and delete the copy. All state-changing requests are
 * protected by the admin filter and carry a CSRF token.</p>
 */
@Controller
@RequestMapping("/settings/prompts")
public class PromptTemplateController {

    private final PromptTemplateService templates;
    private final AdminAccessService access;
    private final ScenarioCatalog scenarios;

    public PromptTemplateController(PromptTemplateService templates, AdminAccessService access,
            ScenarioCatalog scenarios) {
        this.templates = templates;
        this.access = access;
        this.scenarios = scenarios;
    }

    @GetMapping
    public String catalog(Model model, HttpServletRequest request) {
        model.addAttribute("templates", templates.catalog());
        model.addAttribute("scenarios", scenarios.all());
        model.addAttribute("csrfToken", access.csrfToken(request.getSession(true)));
        model.addAttribute("adminTokenConfigured", access.tokenConfigured());
        return "prompts";
    }

    @PostMapping("/{id}/copy")
    public String copy(@PathVariable String id,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "description", required = false) String description,
            RedirectAttributes redirect) {
        try {
            String created = templates.copyBuiltIn(id, name, description);
            redirect.addFlashAttribute("message", "Создана пользовательская копия шаблона: " + created);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings/prompts";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable String id,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "description", required = false) String description,
            @RequestParam("body") String body,
            RedirectAttributes redirect) {
        try {
            templates.update(id, title, description, body);
            redirect.addFlashAttribute("message", "Шаблон сохранён. Новая версия применится к следующей генерации.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings/prompts";
    }

    @PostMapping("/{id}/restore")
    public String restore(@PathVariable String id, RedirectAttributes redirect) {
        try {
            templates.restore(id);
            redirect.addFlashAttribute("message", "Шаблон восстановлен из встроенной версии.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings/prompts";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes redirect) {
        try {
            templates.delete(id);
            redirect.addFlashAttribute("message", "Пользовательский шаблон удалён.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings/prompts";
    }
}
