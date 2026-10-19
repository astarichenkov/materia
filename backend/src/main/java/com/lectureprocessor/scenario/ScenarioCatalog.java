package com.lectureprocessor.scenario;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.lectureprocessor.processing.ProcessingProfile;

/** Single authoritative catalogue of processing scenarios. */
@Service
public class ScenarioCatalog {

    private static final List<ProcessingScenario> ORDERED = List.of(
            ProcessingScenario.NOTES,
            ProcessingScenario.SHORT_SUMMARY,
            ProcessingScenario.RECIPE,
            ProcessingScenario.DIALOGUE,
            ProcessingScenario.INSTRUCTION,
            ProcessingScenario.TRANSLATION,
            ProcessingScenario.VERBATIM,
            ProcessingScenario.ACADEMIC_NOTES,
            ProcessingScenario.ARTICLE,
            ProcessingScenario.FAQ,
            ProcessingScenario.MEETING_PROTOCOL,
            ProcessingScenario.METHODICAL_GUIDE,
            ProcessingScenario.CLEAN_TRANSCRIPT);

    public List<ProcessingScenario> all() {
        return ORDERED;
    }

    public List<ProcessingScenario> byCategory(ScenarioCategory category) {
        return ORDERED.stream().filter(scenario -> scenario.category() == category).toList();
    }

    public Optional<ProcessingScenario> find(String id) {
        return Optional.ofNullable(ProcessingScenario.fromId(id));
    }

    /** Resolves the scenario of an effective profile; empty for legacy profiles. */
    public Optional<ProcessingScenario> of(ProcessingProfile profile) {
        return profile == null ? Optional.empty() : Optional.ofNullable(profile.scenario());
    }

    public ProcessingScenario require(String id) {
        ProcessingScenario scenario = ProcessingScenario.fromId(id);
        if (scenario == null) {
            throw new IllegalArgumentException("Неизвестный сценарий обработки: " + id);
        }
        return scenario;
    }
}
