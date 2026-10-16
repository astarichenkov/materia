package com.lectureprocessor.support;

import org.springframework.stereotype.Component;

/**
 * Resolves the effective principal for support tool execution. When the
 * orchestrator runs tools it sets an explicit thread-local principal; direct
 * local MCP calls fall back to the installation principal (the documented local
 * trust model).
 */
@Component
public class SupportScopeGuard {

    private final SupportAccessService access;

    public SupportScopeGuard(SupportAccessService access) {
        this.access = access;
    }

    public SupportPrincipal current() {
        return SupportAccessContext.current().orElseGet(access::installPrincipal);
    }
}
