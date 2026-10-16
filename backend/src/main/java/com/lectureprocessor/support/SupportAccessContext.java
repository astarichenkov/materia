package com.lectureprocessor.support;

import java.util.Optional;

/**
 * Thread-local authorization context for support tool execution. Tools invoked
 * in-process by the orchestrator (or over the local MCP endpoint) must run under
 * an explicit principal instead of inheriting ambient authority.
 */
public final class SupportAccessContext {

    private static final ThreadLocal<SupportPrincipal> CURRENT = new ThreadLocal<>();

    private SupportAccessContext() {
    }

    public static void set(SupportPrincipal principal) {
        if (principal == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(principal);
        }
    }

    public static Optional<SupportPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs {@code action} under {@code principal}, restoring the previous context. */
    public static <T> T callWith(SupportPrincipal principal, java.util.function.Supplier<T> action) {
        SupportPrincipal previous = CURRENT.get();
        set(principal);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                set(previous);
            }
        }
    }
}
