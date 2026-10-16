package com.lectureprocessor.support;

/** Result of one support tool invocation, already scoped and size-bounded. */
public record SupportToolCallResult(String name, boolean ok, String text, long durationMs) {
}
