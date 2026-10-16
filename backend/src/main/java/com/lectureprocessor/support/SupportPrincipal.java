package com.lectureprocessor.support;

/**
 * Authorization principal for support operations.
 *
 * <p>The application has no full user system; this is the explicit local trust
 * context. {@code scope} is the installation boundary, {@code owner} is a
 * per-session id used when {@code app.support.isolation=session}.</p>
 *
 * @param scope installation scope
 * @param owner per-session owner id
 */
public record SupportPrincipal(String scope, String owner) {

    public SupportPrincipal {
        scope = scope == null || scope.isBlank() ? "local-install" : scope;
        owner = owner == null || owner.isBlank() ? "local" : owner;
    }
}
