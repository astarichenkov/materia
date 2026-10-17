package com.lectureprocessor.fileagent;

/** Lifecycle of one immutable change proposal. */
public enum ProposalStatus {

    CREATED,
    AWAITING_APPROVAL,
    APPROVED,
    APPLYING,
    VERIFYING,
    COMPLETED,
    REJECTED,
    FAILED,
    CONFLICT;

    public boolean terminal() {
        return this == COMPLETED || this == REJECTED || this == FAILED || this == CONFLICT;
    }
}
