package com.lectureprocessor.fileagent;

import org.springframework.stereotype.Service;

import com.lectureprocessor.fileagent.model.ChangeProposalDraft;
import com.lectureprocessor.fileagent.model.ValidatedProposal;

/** Final semantic checks before a validated proposal is persisted. */
@Service
public class FileAgentResultValidator {

    public ValidatedProposal validate(ChangeProposalDraft draft, ValidatedProposal proposal) {
        if (proposal == null || proposal.changes().isEmpty()) {
            throw new FileAgentException("Пустое предложение изменений");
        }
        if (proposal.diff() == null || proposal.diff().isBlank()) {
            throw new FileAgentException("Предложение не содержит diff");
        }
        if (proposal.summary() == null || proposal.summary().isBlank()) {
            throw new FileAgentException("Предложение не содержит описания");
        }
        if (draft.changes().stream().anyMatch(change -> change.content() == null)) {
            throw new FileAgentException("Изменение без содержимого отклонено");
        }
        return proposal;
    }
}
