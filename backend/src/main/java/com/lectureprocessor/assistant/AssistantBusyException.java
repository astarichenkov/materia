package com.lectureprocessor.assistant;

/** Assistant generation queue is saturated; the caller should retry later. */
public class AssistantBusyException extends RuntimeException {

    public AssistantBusyException(String message) {
        super(message);
    }
}
