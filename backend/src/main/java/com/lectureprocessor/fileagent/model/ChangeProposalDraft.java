package com.lectureprocessor.fileagent.model;

import java.util.List;

/** Parsed proposal JSON before deterministic validation. */
public record ChangeProposalDraft(String summary, List<ProposedChangeDraft> changes, List<String> notes) {
}
