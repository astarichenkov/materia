package com.lectureprocessor.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.lectureprocessor.text.PromptTemplates;

/**
 * Matrix of material type × output format × prompt template.
 *
 * <p>A template is never switched silently: incompatible combinations are still
 * allowed (the user explicitly selected them) but the plan shows a warning and
 * explains which structure will actually be produced, together with the
 * recommended template. The generation prompt receives the same format contract
 * so an interview template cannot quietly turn a brief summary into an FAQ.</p>
 */
@Service
public class TemplateCompatibility {

    public enum Severity {
        INFO, WARNING, INCOMPATIBLE
    }

    public record Issue(Severity severity, String message, String recommendedTemplate) {
    }

    private static final Set<MaterialType> INTERVIEW_LIKE = Set.of(
            MaterialType.AUTO, MaterialType.INTERVIEW, MaterialType.DISCUSSION, MaterialType.WEBINAR);

    private final PromptTemplates prompts;

    public TemplateCompatibility(PromptTemplates prompts) {
        this.prompts = prompts;
    }

    public List<Issue> evaluate(ProcessingProfile profile) {
        List<Issue> issues = new ArrayList<>();
        String prompt = profile.resolvedPrompt();
        OutputFormat format = profile.outputFormat();
        MaterialType material = profile.materialType();
        String recommended = format.defaultPrompt();

        boolean interviewTemplate = PromptTemplates.NOTES_INTERVIEW.equals(prompt);
        boolean faqTemplate = PromptTemplates.NOTES_FAQ.equals(prompt);
        boolean cleanTemplate = prompt != null && prompt.startsWith("clean-transcript");
        if (prompt == null || prompt.isBlank()) {
            // No-LLM scenarios have no template: nothing to compare.
            return issues;
        }

        if ((interviewTemplate || faqTemplate) && !INTERVIEW_LIKE.contains(material)) {
            issues.add(new Issue(Severity.WARNING,
                    "Шаблон вопросов и ответов выбран для материала типа «"
                            + material.label()
                            + "». Вопросы появятся только там, где они действительно есть в тексте; "
                            + "структура документа будет соответствовать формату «" + format.label()
                            + "», а не превратится в интервью.",
                    recommended));
        }
        if (interviewTemplate && (format == OutputFormat.BRIEF_NOTES
                || format == OutputFormat.DETAILED_NOTES
                || format == OutputFormat.ARTICLE
                || format == OutputFormat.METHODICAL_GUIDE)) {
            issues.add(new Issue(Severity.WARNING,
                    "Шаблон интервью не совпадает с форматом «" + format.label()
                            + "»: итоговый документ будет подчинён требованиям формата "
                            + "(без фиктивных участников и повторяющихся вопросов). "
                            + "Рекомендуется шаблон " + recommended + ".",
                    recommended));
        }
        if (faqTemplate && format != OutputFormat.FAQ
                && format != OutputFormat.INTERVIEW_QA) {
            issues.add(new Issue(Severity.WARNING,
                    "FAQ-шаблон применён к формату «" + format.label()
                            + "»: количество вопросов ограничивается содержанием материала.",
                    recommended));
        }
        if (format == OutputFormat.INTERVIEW_QA && !INTERVIEW_LIKE.contains(material)) {
            issues.add(new Issue(Severity.WARNING,
                    "Формат «Вопросы и ответы» выбран для материала типа «" + material.label()
                            + "». Если в материале нет вопросов, документ будет коротким — "
                            + "это ожидаемо и лучше выдуманных вопросов.",
                    recommended));
        }
        if (cleanTemplate && !format.cleanTranscript()) {
            issues.add(new Issue(Severity.INCOMPATIBLE,
                    "Шаблон очистки транскрипции не создаёт конспект: выбранный формат «"
                            + format.label() + "» требует другого шаблона.",
                    recommended));
        }
        if (format.cleanTranscript() && !cleanTemplate) {
            issues.add(new Issue(Severity.INCOMPATIBLE,
                    "Для формата «Очищенная транскрипция» нужен шаблон очистки, а выбран "
                            + prompt + ". Итог может не соответствовать формату.",
                    recommended));
        }
        if (!prompts.exists(prompt)) {
            issues.add(new Issue(Severity.INCOMPATIBLE,
                    "Шаблон " + prompt + " не найден. Будет использован " + recommended + ".",
                    recommended));
        }
        return issues;
    }

    public boolean hasIncompatible(List<Issue> issues) {
        return issues.stream().anyMatch(issue -> issue.severity() == Severity.INCOMPATIBLE);
    }
}
