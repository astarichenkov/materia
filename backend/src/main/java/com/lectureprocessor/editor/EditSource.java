package com.lectureprocessor.editor;

/**
 * Provenance of a document revision. Human edits are never overwritten by
 * automated regeneration: automation always appends a new revision.
 */
public enum EditSource {
    /** Raw output of the speech recognition tool. */
    WHISPER,
    /** Machine translation produced by the translation LLM. */
    TRANSLATION_LLM,
    /** Notes produced by the text-processing LLM. */
    TEXT_PROCESSING_LLM,
    /** Direct human edit. Highest priority, never overwritten silently. */
    HUMAN,
    /** Human accepted an AI suggestion (segment retry / notes regeneration). */
    AI_ASSISTED
}
