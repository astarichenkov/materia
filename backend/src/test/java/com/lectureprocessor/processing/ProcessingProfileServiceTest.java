package com.lectureprocessor.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmSettingsService;
import com.lectureprocessor.text.PromptTemplates;

class ProcessingProfileServiceTest {

    private final ProcessingProfileRepository repository = mock(ProcessingProfileRepository.class);
    private final LlmSettingsService settings = mock(LlmSettingsService.class);
    private final PromptTemplates prompts = new PromptTemplates();
    private final ProcessingProfileService service =
            new ProcessingProfileService(repository, settings, prompts);

    @Test
    void legacyDefaultKeepsConfiguredPrompt() {
        when(repository.find("L1")).thenReturn(Optional.empty());
        when(settings.current()).thenReturn(new LlmRunConfig("ollama", "m", 0.1, 1024, 4096, false,
                PromptTemplates.STRUCTURED_NOTES_V2));

        ProcessingProfile profile = service.get("L1");

        assertThat(profile.explicit()).isFalse();
        assertThat(profile.resolvedPrompt()).isEqualTo(PromptTemplates.STRUCTURED_NOTES_V2);
        assertThat(profile.instructions()).isNull();
    }

    @Test
    void savingAFormatWithoutExplicitPromptUsesFormatDefault() {
        ProcessingProfile profile = service.save("L1", "PRACTICAL_TUTORIAL", "STEP_BY_STEP",
                "DETAILED", "");

        assertThat(profile.explicit()).isTrue();
        assertThat(profile.resolvedPrompt()).isEqualTo("notes-howto-v1");
        assertThat(profile.fingerprintPart()).contains("PRACTICAL_TUTORIAL").contains("STEP_BY_STEP");
        verify(repository).save("L1", profile);
    }

    @Test
    void unknownPromptTemplateIsRejected() {
        assertThatThrownBy(() -> service.save("L1", "AUTO", "BRIEF_NOTES", "STANDARD", "no-such-prompt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Неизвестный шаблон");
    }

    @Test
    void profileInstructionsDescribeMaterialAndDetail() {
        ProcessingProfile profile = ProcessingProfile.explicit(MaterialType.INTERVIEW,
                OutputFormat.INTERVIEW_QA, DetailLevel.DETAILED, null);
        assertThat(profile.instructions())
                .contains("Интервью")
                .contains("ПОДРОБНО");
    }

    @Test
    void storedProfileIsReturnedAsIs() {
        ProcessingProfile stored = ProcessingProfile.explicit(MaterialType.WEBINAR,
                OutputFormat.FAQ, DetailLevel.BRIEF, "notes-faq-v1");
        when(repository.find("L1")).thenReturn(Optional.of(stored));
        assertThat(service.get("L1")).isEqualTo(stored);
        assertThat(service.hasExplicitProfile("L1")).isTrue();
    }

    @Test
    void previewDoesNotPersist() {
        ProcessingProfile preview = service.preview("MEETING", "MEETING_PROTOCOL", "STANDARD", null);
        assertThat(preview.outputFormat()).isEqualTo(OutputFormat.MEETING_PROTOCOL);
        verify(repository, org.mockito.Mockito.never()).save(anyString(), any());
    }
}
