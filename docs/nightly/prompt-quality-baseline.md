# Prompt quality & regeneration progress — baseline (Nightly Sprint 2)

Date: 2026-10-19. Branch `feature/nightly-prompt-quality`, base
`feature/nightly-roadmap` @ `95f7b1b`.

This document records the defects reproduced on the code and the root causes
found before any fix. It is not a report of intentions.

## Baseline observations

### BUG-1 — повторная генерация не показывает свой прогресс

Reproduced from code: `JobStatusService.view()` computed `progressPercent` from
**all** completed checkpoints of the lecture:

```java
int completed = checkpoints.stream()
        .filter(c -> c.state() == StageState.COMPLETED).count();
int progressPercent = Math.round(completed * 100.0 / allStages.length);
```

After a completed pipeline (10/10 checkpoints) a new format-only run has to
execute only `TEXT_PROCESSING` and `EXPORT`. Until the first stage flips its
checkpoint to `RUNNING`, the UI shows 10/10 = **100%**. There was no per-run
record, therefore after a page reload the UI could not distinguish "this run"
from "the lecture was completed last week".

Root cause: progress was lecture-scoped, not run-scoped; no persisted run plan.

### BUG-2 — «обработка остановлена» на успешном результате

`fragments/status.html` rendered the suffix
`· обработка остановлена, дальнейшие этапы не запускались`
whenever `job.terminal == true`. `COMPLETED` is terminal, so a successful export
showed the cancellation wording. Additionally `JobStatusService.message()` for
`COMPLETED` returned `""`.

Root cause: template used `terminal` only; no distinction between successful
and unsuccessful terminal states.

### BUG-3 — массовое повторение Q&A (Baseline)

Reproduced in `TextProcessingService.generateStructuredNotes()`:

1. transcript is split into chunks with `llm-chunk-overlap-chars=400`, so the
   same sentences exist in adjacent chunks;
2. every chunk is sent independently with the same system prompt;
3. chunk outputs were concatenated verbatim:
   `String.join("\n\n", sections...)`;
4. no deduplication of headings, paragraphs or questions existed;
5. `summarize()` also received a material where the repeated blocks were already
   present.

A small-model run repeats the same Q&A answer per chunk, producing dozens of
identical questions. Reproduced by
`TextProcessingServiceTest#removesRepeatedQaBlocksAcrossChunks` and
`NotesAssemblerTest#mergesIdenticalHeadingsAndDropsDuplicateQuestions`.

Root cause: per-chunk independent generation + verbatim concatenation + no
structural assembly.

### BUG-4 — краткий конспект превращается в интервью

`ProcessingProfile.resolvedPrompt()` returned the user-selected template
(`notes-interview-v1`) regardless of `OutputFormat.BRIEF_NOTES`. The profile
instructions («Тип исходного материала…») were appended **inside the user
message**, directly before the transcript, so the model treated task metadata as
content. The interview/FAQ prompts asked for a fixed number of Q&A pairs, which
forced the model to invent or repeat questions.

Root cause: no format contract, no compatibility matrix, instructions placed in
the transcript message, prompts requiring a minimum number of questions.

### BUG-5 — план обещает артефакты при no-op

`RerunPlanService` appended `notes.json`, `notes.md`, `notes.docx` and
`новый вариант в истории результатов` to `producedArtifacts` unconditionally,
also when every stage was `REUSE`/`SKIP`. The controller still submitted a job.

Root cause: artifacts were derived from the *requested format*, not from the
stages that will actually run; no `noop` semantics.

### BUG-6 — preview и execution расходятся

- `RerunPlanService` used ad-hoc file checks and `profileChanged`, while the
  coordinator used `StageExecutor.inputFingerprint()`.
- `TextProcessingStage.inputFingerprint()` used `llmSettings.current()`, but
  execution used `LlmTaskResolver.resolve(LECTURE_PROCESSING)` (per-task model,
  cloud provider, temperature).
- `RerunPlanService` did not know about the resolved model/temperature.

Root cause: two independent planning code paths; fingerprint built from the
global default instead of the actual resolved task configuration.

## Regression coverage added

| Defect | Test |
|---|---|
| progress 100% during a new run | `JobStatusServiceTest#newRunDoesNotInheritPreviousCompletedPipelinePercent` |
| false "stopped" message | `JobStatusServiceTest#completedRunShowsSuccessMessageInsteadOfStoppedMessage` |
| duplicated Q&A | `TextProcessingServiceTest#removesRepeatedQaBlocksAcrossChunks`, `NotesAssemblerTest` |
| interview template on webinar | `PromptTemplateServiceTest#interviewTemplateOnWebinarIsWarnedButNotSilentlyReplaced`, `RerunPlanServiceTest#incompatibleInterviewTemplateIsWarnedAboutButNotSilentlySwitched` |
| no-op plan promises artifacts | `RerunPlanServiceTest#unchangedLegacyProfileIsANoopAndPromisesNoArtifacts` |
| plan vs execution | `PipelineCoordinatorIntegrationTest#changingPromptRerunsTextAndExportButNotTranscription` (asserts the persisted run plan) |
