package com.lectureprocessor.support;

/** A bounded trace entry of one support tool call, shown in the UI. */
public record SupportToolTrace(String name, boolean ok, long durationMs, String summary) {
}
