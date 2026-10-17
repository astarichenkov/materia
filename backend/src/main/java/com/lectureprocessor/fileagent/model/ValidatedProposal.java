package com.lectureprocessor.fileagent.model;

import java.util.List;

/** Deterministically validated proposal ready for approval persistence. */
public record ValidatedProposal(
        String summary,
        List<ValidatedChange> changes,
        String diff,
        List<String> notes,
        String verificationProfile) {
}
