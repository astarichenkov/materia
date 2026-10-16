package com.lectureprocessor.support;

/**
 * A citation behind a support answer. {@code kind} separates the trust layers:
 * {@code DOC} (documentation RAG), {@code JOB}/{@code STAGE}/{@code DOWNLOAD}
 * (confirmed persisted facts), {@code TICKET} (conversation context) and
 * {@code MODEL} (provider health).
 *
 * @param kind    evidence kind
 * @param source  stable reference, e.g. {@code README.md#12} or {@code job#42}
 * @param title   short human title
 * @param detail  bounded detail (redacted)
 * @param score   retrieval score for docs, null otherwise
 */
public record SupportCitation(String kind, String source, String title, String detail, Double score) {

    public static SupportCitation doc(String source, String title, String detail, Double score) {
        return new SupportCitation("DOC", source, title, detail, score);
    }

    public static SupportCitation fact(String kind, String source, String title, String detail) {
        return new SupportCitation(kind, source, title, detail, null);
    }
}
