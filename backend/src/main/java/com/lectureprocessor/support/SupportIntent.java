package com.lectureprocessor.support;

/** Coarse routing category for a support question. */
public enum SupportIntent {

    /** Product/FAQ question answered from documentation RAG. */
    GENERAL_FAQ,

    /** Question about the state of a lecture/job. */
    JOB_DIAGNOSTICS,

    /** Question tied to a support ticket and its history. */
    TICKET_CONTEXT,

    /** Documentation + diagnostics + ticket history combined. */
    MIXED
}
