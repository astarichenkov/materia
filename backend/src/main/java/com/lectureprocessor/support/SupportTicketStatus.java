package com.lectureprocessor.support;

import java.util.Locale;

/** Lifecycle status of a support ticket. */
public enum SupportTicketStatus {

    OPEN("Открыт"),
    IN_PROGRESS("В работе"),
    RESOLVED("Решён"),
    CLOSED("Закрыт");

    private final String label;

    SupportTicketStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean terminal() {
        return this == RESOLVED || this == CLOSED;
    }

    public static SupportTicketStatus from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Не задан статус тикета");
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Неизвестный статус тикета: " + value);
        }
    }
}
