package com.lectureprocessor.fileagent;

/** Lifecycle of one autonomous file-agent task. */
public enum FileAgentTaskStatus {

    CREATED,
    PLANNING,
    SEARCHING,
    ANALYZING,
    PROPOSING,
    AWAITING_APPROVAL,
    APPROVED,
    APPLYING,
    VERIFYING,
    COMPLETED,
    REJECTED,
    FAILED,
    CANCELLED,
    CONFLICT;

    public boolean terminal() {
        return isTerminal();
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED || this == FAILED
                || this == CANCELLED || this == CONFLICT;
    }

    public boolean awaitingApproval() {
        return this == AWAITING_APPROVAL;
    }
}
