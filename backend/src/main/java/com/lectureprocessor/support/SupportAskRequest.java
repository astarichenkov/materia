package com.lectureprocessor.support;

/**
 * A support question with optional ticket/lecture context.
 *
 * @param question  user question (trusted instruction intent, untrusted content)
 * @param ticketId  optional ticket to continue
 * @param lectureId optional lecture context (auto-linked from the lecture page)
 * @param sessionId optional UI session id (informational only)
 */
public record SupportAskRequest(String question, Long ticketId, String lectureId, String sessionId) {
}
