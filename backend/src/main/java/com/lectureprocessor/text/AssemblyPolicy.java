package com.lectureprocessor.text;

/**
 * How chunk outputs are combined into one document.
 *
 * <p>A single universal "deduplicate everything" pass is wrong for universal
 * content: a repeated ingredient in a recipe, a repeated line in a dialogue or a
 * repeated step in an instruction can be meaningful. The policy is selected by
 * the processing scenario, not by the LLM.</p>
 */
public enum AssemblyPolicy {
    /** Merge equal headings and drop identical/near-identical blocks (notes). */
    SEMANTIC(true, true),
    /** Keep every block and its order; merge only equal headings. */
    CHRONOLOGICAL(false, true),
    /** Keep every block and its order (recipe, instruction). */
    STRUCTURED(false, true),
    /** Preserve the model output verbatim per chunk, including repetitions. */
    DIALOGUE(false, false),
    /** Not produced by the LLM: the transcript is exported as-is. */
    LOSSLESS(false, false);

    private final boolean deduplicates;
    private final boolean mergesHeadings;

    AssemblyPolicy(boolean deduplicates, boolean mergesHeadings) {
        this.deduplicates = deduplicates;
        this.mergesHeadings = mergesHeadings;
    }

    public boolean deduplicates() {
        return deduplicates;
    }

    public boolean mergesHeadings() {
        return mergesHeadings;
    }
}
