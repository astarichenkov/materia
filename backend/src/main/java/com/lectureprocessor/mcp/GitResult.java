package com.lectureprocessor.mcp;

/** Outcome of a bounded, read-only git invocation. */
public record GitResult(int exitCode, String output, boolean timedOut, boolean gitUnavailable) {

    public boolean ok() {
        return !timedOut && !gitUnavailable && exitCode == 0;
    }
}
