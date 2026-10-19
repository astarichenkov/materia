package com.lectureprocessor.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;

import com.lectureprocessor.processing.OutputFormat;
import com.lectureprocessor.scenario.ProcessingScenario;

/**
 * Prompt template catalogue and user-owned copies.
 *
 * <p>Built-in templates are read-only. A user can create a copy, edit it, roll
 * back to the source version or delete the copy. Every edit increments the
 * version, which is part of the text-processing fingerprint, so a changed
 * template re-generates the notes without touching the transcript.</p>
 */
@Service
public class PromptTemplateService {

    private static final int MAX_BODY_LENGTH = 12000;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    public record TemplateView(
            String id,
            String name,
            String label,
            String description,
            boolean builtIn,
            int version,
            String body,
            String sourceTemplate,
            String structure,
            String recommendedFor,
            boolean editable) {
    }

    public record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult failed(List<String> errors) {
            return new ValidationResult(false, List.copyOf(errors));
        }
    }

    private final PromptTemplates prompts;
    private final PromptTemplateRepository repository;

    public PromptTemplateService(PromptTemplates prompts, PromptTemplateRepository repository) {
        this.prompts = prompts;
        this.repository = repository;
    }

    /** Custom bodies must be available before the first generation starts. */
    @EventListener(ApplicationReadyEvent.class)
    public void loadCustomTemplates() {
        try {
            for (PromptTemplateRepository.StoredTemplate stored : repository.findAll()) {
                if (!stored.builtIn() && "ACTIVE".equals(stored.status())) {
                    prompts.registerCustom(stored.name(), stored.body());
                }
            }
        } catch (RuntimeException e) {
            // A fresh database may not have the table yet in some test contexts.
        }
    }

    public List<TemplateView> catalog() {
        List<TemplateView> result = new ArrayList<>();
        for (PromptTemplates.Template template : prompts.list()) {
            result.add(new TemplateView(template.name(), template.name(), template.label(),
                    template.description(), true, 1, prompts.get(template.name()), null,
                    structureFor(template.name()), recommendedFor(template.name()), false));
        }
        for (PromptTemplateRepository.StoredTemplate stored : repository.findAll()) {
            result.add(new TemplateView(stored.id(), stored.name(), stored.name(),
                    stored.description(), false, stored.version(), stored.body(),
                    stored.sourceTemplate(), structureFor(stored.sourceTemplate()),
                    recommendedFor(stored.sourceTemplate()), true));
        }
        return result;
    }

    /** Templates that can produce a document and are offered in the profile form. */
    public List<PromptTemplates.Template> selectable() {
        List<PromptTemplates.Template> result = new ArrayList<>(prompts.list().stream()
                .filter(this::isDocumentTemplate).toList());
        for (PromptTemplateRepository.StoredTemplate stored : repository.findAll()) {
            result.add(new PromptTemplates.Template(stored.name(), stored.name(),
                    stored.description() == null ? "Пользовательский шаблон" : stored.description()));
        }
        return result;
    }

    public TemplateView get(String id) {
        return catalog().stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Шаблон не найден"));
    }

    public ValidationResult validate(String name, String body) {
        List<String> errors = new ArrayList<>();
        if (name == null || name.isBlank()) {
            errors.add("Укажите название шаблона.");
        } else if (name.length() > 200) {
            errors.add("Название не должно превышать 200 символов.");
        }
        if (body == null || body.isBlank()) {
            errors.add("Текст шаблона не может быть пустым.");
        } else {
            if (body.length() > MAX_BODY_LENGTH) {
                errors.add("Текст шаблона слишком длинный (максимум " + MAX_BODY_LENGTH + " символов).");
            }
            long open = body.chars().filter(ch -> ch == '{').count();
            long close = body.chars().filter(ch -> ch == '}').count();
            if (open != close) {
                errors.add("Незакрытая подстановка в шаблоне: проверьте фигурные скобки.");
            }
            String lower = body.toLowerCase(Locale.ROOT);
            if (lower.contains("api key") || lower.contains("api_key") || lower.contains("token=")) {
                errors.add("Шаблон не должен содержать секреты или ключи API.");
            }
        }
        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(errors);
    }

    /** Creates an editable user copy of a built-in template. */
    public String copyBuiltIn(String sourceName, String newName, String description) {
        if (!prompts.exists(sourceName) || prompts.isCustom(sourceName)) {
            throw new IllegalArgumentException("Копировать можно только встроенный шаблон.");
        }
        String label = newName == null || newName.isBlank()
                ? "Копия: " + labelFor(sourceName) : newName.strip();
        ValidationResult validation = validate(label, prompts.builtIn(sourceName));
        if (!validation.valid()) {
            throw new IllegalArgumentException(String.join(" ", validation.errors()));
        }
        String id = customId(sourceName);
        repository.insert(id, id, description == null || description.isBlank()
                        ? "Пользовательская копия шаблона " + sourceName : description.strip(),
                sourceName, prompts.builtIn(sourceName), "ALL", "ALL");
        refresh(id);
        return id;
    }

    public void update(String id, String title, String description, String body) {
        PromptTemplateRepository.StoredTemplate stored = requireCustom(id);
        ValidationResult validation = validate(title == null || title.isBlank() ? stored.name() : title, body);
        if (!validation.valid()) {
            throw new IllegalArgumentException(String.join(" ", validation.errors()));
        }
        repository.update(id, stored.name(),
                description == null || description.isBlank() ? stored.description() : description.strip(),
                body.strip());
        refresh(id);
    }

    /** Rolls a user copy back to the built-in source body (version increments). */
    public void restore(String id) {
        PromptTemplateRepository.StoredTemplate stored = requireCustom(id);
        if (stored.sourceTemplate() == null) {
            throw new IllegalArgumentException("У этой копии нет встроенного источника.");
        }
        repository.update(id, stored.name(), stored.description(),
                prompts.builtIn(stored.sourceTemplate()));
        refresh(id);
    }

    public void delete(String id) {
        PromptTemplateRepository.StoredTemplate stored = requireCustom(id);
        repository.delete(id);
        prompts.unregisterCustom(stored.name());
    }

    private PromptTemplateRepository.StoredTemplate requireCustom(String id) {
        PromptTemplateRepository.StoredTemplate stored = repository.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон не найден"));
        if (stored.builtIn()) {
            throw new IllegalArgumentException("Встроенный шаблон нельзя изменять или удалять.");
        }
        return stored;
    }

    private void refresh(String id) {
        repository.find(id).ifPresent(stored ->
                prompts.registerCustom(stored.name(), stored.body()));
    }

    private String customId(String sourceName) {
        String prefix = sourceName.startsWith("clean-transcript")
                ? "clean-transcript-custom-" : "notes-custom-";
        String slug = Normalizer.normalize(sourceName, Normalizer.Form.NFD)
                .replaceAll("[^a-zA-Z0-9-]", "-")
                .replaceAll("-+", "-");
        return prefix + slug + "-" + System.currentTimeMillis() % 1_000_000
                + "-" + SEQUENCE.incrementAndGet();
    }

    private boolean isDocumentTemplate(PromptTemplates.Template template) {
        return template.name().startsWith("notes-")
                || template.name().startsWith("clean-transcript")
                || template.name().startsWith("structured-notes")
                || template.name().startsWith("scenario-");
    }

    private String labelFor(String name) {
        return prompts.list().stream().filter(t -> t.name().equals(name))
                .map(PromptTemplates.Template::label).findFirst().orElse(name);
    }

    private String recommendedFor(String name) {
        java.util.List<String> targets = new java.util.ArrayList<>();
        java.util.Arrays.stream(OutputFormat.values())
                .filter(format -> java.util.Objects.equals(format.defaultPrompt(), name))
                .map(OutputFormat::label)
                .forEach(targets::add);
        java.util.Arrays.stream(ProcessingScenario.values())
                .filter(scenario -> java.util.Objects.equals(scenario.recommendedPrompt(), name))
                .map(ProcessingScenario::title)
                .forEach(targets::add);
        return targets.isEmpty() ? "дополнительные сценарии"
                : String.join(", ", new java.util.LinkedHashSet<>(targets));
    }

    private String structureFor(String name) {
        if (name == null) {
            return "Структура задаётся шаблоном пользователя.";
        }
        return java.util.Arrays.stream(OutputFormat.values())
                .filter(format -> java.util.Objects.equals(format.defaultPrompt(), name))
                .map(OutputFormat::structureContract)
                .findFirst()
                .orElse("Структура описана в тексте шаблона.");
    }
}
