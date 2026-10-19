package com.lectureprocessor.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.lectureprocessor.processing.OutputFormat;
import com.lectureprocessor.processing.ProcessingProfile;
import com.lectureprocessor.processing.TemplateCompatibility;
import com.lectureprocessor.processing.TemplateCompatibility.Issue;
import com.lectureprocessor.processing.TemplateCompatibility.Severity;
import com.lectureprocessor.text.PromptTemplates;

/**
 * Checks a scenario/profile/template combination before anything is generated.
 *
 * <p>There are no hidden template substitutions: an incompatible combination is
 * blocked (severity {@code INCOMPATIBLE}) with an explanation and the
 * recommended template. A merely unusual combination is a warning and still
 * runs, because the user may deliberately want an article from an interview.</p>
 */
@Service
public class ScenarioCompatibility {

    private final PromptTemplates prompts;
    private final TemplateCompatibility templateCompatibility;

    public ScenarioCompatibility(PromptTemplates prompts,
            TemplateCompatibility templateCompatibility) {
        this.prompts = prompts;
        this.templateCompatibility = templateCompatibility;
    }

    public List<Issue> evaluate(ProcessingProfile profile) {
        List<Issue> issues = new ArrayList<>();
        if (profile == null) {
            return issues;
        }
        ProcessingScenario scenario = profile.scenario();
        OutputFormat format = profile.outputFormat();

        if (scenario != null && !scenario.usesLlm() && format.usesLlm()) {
            issues.add(new Issue(Severity.INCOMPATIBLE,
                    "Сценарий «" + scenario.title() + "» не использует LLM, а формат «"
                            + format.label() + "» требует генерации текста. Выберите формат «"
                            + scenario.recommendedFormat().label() + "» или другой сценарий.",
                    scenario.recommendedFormat().label()));
        }
        if (scenario != null && !scenario.usesLlm() && profile.promptTemplate() != null
                && !profile.promptTemplate().isBlank()) {
            issues.add(new Issue(Severity.INFO,
                    "Сценарий «" + scenario.title() + "» собирает документ из транскрипции: "
                            + "шаблон " + profile.promptTemplate() + " не применяется.",
                    null));
        }
        if (scenario != null && !scenario.supports(profile.materialType())) {
            issues.add(new Issue(Severity.WARNING,
                    "Сценарий «" + scenario.title() + "» обычно не применяется к типу «"
                            + profile.materialType().label() + "». Это не запрещено: результат "
                            + "будет построен только по содержанию материала.",
                    null));
        }

        if (scenario != null && scenario.usesLlm()) {
            String prompt = profile.resolvedPrompt();
            if (prompt == null || prompt.isBlank() || !prompts.exists(prompt)) {
                issues.add(new Issue(Severity.INCOMPATIBLE,
                        "Шаблон " + Objects.toString(prompt, "(не задан)")
                                + " не найден. Выберите рекомендуемый шаблон "
                                + scenario.recommendedPrompt() + ".",
                        scenario.recommendedPrompt()));
            } else if (scenario.recommendedPrompt() != null
                    && !scenario.recommendedPrompt().equals(prompt)
                    && !prompts.isCustom(prompt)) {
                issues.add(new Issue(Severity.WARNING,
                        "Для сценария «" + scenario.title() + "» рекомендуется шаблон "
                                + scenario.recommendedPrompt() + ", выбран " + prompt
                                + ". Результат будет соответствовать выбранному шаблону и контракту "
                                + "формата «" + format.label() + "».",
                        scenario.recommendedPrompt()));
            }
        }

        // Keep the legacy format/template matrix: it still catches clean-transcript
        // versus notes mismatches for profiles created before scenarios existed.
        for (Issue issue : templateCompatibility.evaluate(profile)) {
            if (issues.stream().noneMatch(existing -> existing.message().equals(issue.message()))) {
                issues.add(issue);
            }
        }
        return issues;
    }

    public boolean hasIncompatible(List<Issue> issues) {
        return issues != null && issues.stream()
                .anyMatch(issue -> issue.severity() == Severity.INCOMPATIBLE);
    }

    /** First blocking message, or {@code null}. */
    public String firstBlockingMessage(List<Issue> issues) {
        if (issues == null) {
            return null;
        }
        return issues.stream()
                .filter(issue -> issue.severity() == Severity.INCOMPATIBLE)
                .map(Issue::message)
                .findFirst().orElse(null);
    }
}
