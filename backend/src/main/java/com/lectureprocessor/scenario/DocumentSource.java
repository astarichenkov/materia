package com.lectureprocessor.scenario;

/**
 * Where the produced document comes from.
 *
 * <ul>
 *   <li>{@link #LLM} — the transcript is processed by a language model;</li>
 *   <li>{@link #TRANSCRIPT} — the document is assembled from the transcript
 *       segments without any LLM call (verbatim / translation only).</li>
 * </ul>
 */
public enum DocumentSource {
    LLM,
    TRANSCRIPT;

    public boolean usesLlm() {
        return this == LLM;
    }
}
