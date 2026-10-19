package com.lectureprocessor.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lectureprocessor.processing.MaterialType;
import com.lectureprocessor.processing.OutputFormat;
import com.lectureprocessor.processing.ProcessingProfile;
import com.lectureprocessor.processing.TemplateCompatibility;

class PromptTemplateServiceTest {

    private final PromptTemplates prompts = new PromptTemplates();
    private final PromptTemplateRepository repository = mock(PromptTemplateRepository.class);
    private final PromptTemplateService service = new PromptTemplateService(prompts, repository);

    @BeforeEach
    void setUp() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.find(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void catalogExposesBuiltInsAsReadOnly() {
        assertThat(service.catalog()).isNotEmpty()
                .allSatisfy(template -> assertThat(template.builtIn()).isTrue());
        assertThat(service.catalog()).extracting(PromptTemplateService.TemplateView::id)
                .contains(PromptTemplates.NOTES_INTERVIEW, PromptTemplates.NOTES_FAQ);
    }

    @Test
    void validationRejectsEmptyAndUnbalancedTemplates() {
        assertThat(service.validate("x", " ").valid()).isFalse();
        assertThat(service.validate("x", "Текст {незакрыт").errors())
                .anyMatch(error -> error.contains("фигурные"));
        assertThat(service.validate("x", "Текст с api_key=secret").valid()).isFalse();
        assertThat(service.validate("Название", "Корректный шаблон.").valid()).isTrue();
    }

    @Test
    void userCopyCanBeEditedAndRegisteredForGeneration() {
        PromptTemplateRepository.StoredTemplate stored = new PromptTemplateRepository.StoredTemplate(
                "custom-1", "notes-custom-1", "Копия", PromptTemplates.NOTES_ACADEMIC,
                "Изменённый шаблон", 2, "ALL", "ALL", false, "ACTIVE",
                Instant.now(), Instant.now());
        PromptTemplateRepository.StoredTemplate updated = new PromptTemplateRepository.StoredTemplate(
                "custom-1", "notes-custom-1", "Описание", PromptTemplates.NOTES_ACADEMIC,
                "Новый текст шаблона", 3, "ALL", "ALL", false, "ACTIVE",
                Instant.now(), Instant.now());
        when(repository.find("custom-1")).thenReturn(Optional.of(stored), Optional.of(updated));

        service.update("custom-1", "Мой шаблон", "Описание", "Новый текст шаблона");

        verify(repository).update("custom-1", "notes-custom-1", "Описание", "Новый текст шаблона");
        assertThat(prompts.get("notes-custom-1")).isEqualTo("Новый текст шаблона");
        assertThat(prompts.isCustom("notes-custom-1")).isTrue();
    }

    @Test
    void builtInTemplatesCannotBeDeletedOrEdited() {
        when(repository.find(PromptTemplates.NOTES_ACADEMIC)).thenReturn(Optional.of(
                new PromptTemplateRepository.StoredTemplate(PromptTemplates.NOTES_ACADEMIC,
                        PromptTemplates.NOTES_ACADEMIC, "built-in", null,
                        prompts.get(PromptTemplates.NOTES_ACADEMIC), 1, "ALL", "ALL", true,
                        "ACTIVE", Instant.now(), Instant.now())));

        assertThatThrownBy(() -> service.delete(PromptTemplates.NOTES_ACADEMIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Встроенный");
        assertThatThrownBy(() -> service.update(PromptTemplates.NOTES_ACADEMIC, "x", "", "body"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void interviewTemplateOnWebinarIsWarnedButNotSilentlyReplaced() {
        TemplateCompatibility compatibility = new TemplateCompatibility(prompts);
        ProcessingProfile profile = ProcessingProfile.explicit(MaterialType.WEBINAR,
                OutputFormat.BRIEF_NOTES, com.lectureprocessor.processing.DetailLevel.STANDARD,
                PromptTemplates.NOTES_INTERVIEW);

        List<TemplateCompatibility.Issue> issues = compatibility.evaluate(profile);

        assertThat(issues).isNotEmpty();
        assertThat(profile.resolvedPrompt()).isEqualTo(PromptTemplates.NOTES_INTERVIEW);
        assertThat(issues).anyMatch(issue -> issue.message().contains("интервью"));
    }

    @Test
    void cleanTranscriptTemplateIsIncompatibleWithNotesFormats() {
        TemplateCompatibility compatibility = new TemplateCompatibility(prompts);
        ProcessingProfile profile = ProcessingProfile.explicit(MaterialType.ACADEMIC_LECTURE,
                OutputFormat.DETAILED_NOTES, com.lectureprocessor.processing.DetailLevel.STANDARD,
                PromptTemplates.CLEAN_TRANSCRIPT_V2);

        assertThat(compatibility.hasIncompatible(compatibility.evaluate(profile))).isTrue();
    }
}
