package com.lectureprocessor.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Authorization and CSRF for the support surface.
 *
 * <p>The application has no full user system. The documented trust model is a
 * single trusted local installation: tickets are stamped with an installation
 * {@code scope} and a per-session {@code owner}. Access is always decided from
 * those stored attributes — knowing a ticket id is never sufficient.</p>
 *
 * <p>{@code app.support.isolation=install} (default) lets any local session of
 * the install read its tickets. {@code session} additionally requires a matching
 * owner cookie for horizontal isolation between browser sessions.</p>
 */
@Service
public class SupportAccessService {

    public static final String CSRF_SESSION_ATTRIBUTE = "lp.support.csrf";
    public static final String OWNER_SESSION_ATTRIBUTE = "lp.support.owner";
    public static final String CSRF_PARAMETER = "_csrf";
    public static final String CSRF_HEADER = "X-Support-Csrf";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 24;

    private final SupportProperties properties;

    public SupportAccessService(SupportProperties properties) {
        this.properties = properties;
    }

    /** Trusted local principal used for in-process and local MCP invocations. */
    public SupportPrincipal installPrincipal() {
        return new SupportPrincipal(properties.installScope(), "local");
    }

    /** Resolves the browser principal for a request, creating a session if needed. */
    public SupportPrincipal resolve(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object owner = session.getAttribute(OWNER_SESSION_ATTRIBUTE);
        String value = owner instanceof String s ? s : null;
        if (value == null || value.isBlank()) {
            byte[] raw = new byte[12];
            RANDOM.nextBytes(raw);
            value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            session.setAttribute(OWNER_SESSION_ATTRIBUTE, value);
        }
        return new SupportPrincipal(properties.installScope(), value);
    }

    /** Throws unless the principal may read the ticket under the isolation policy. */
    public void assertCanAccess(SupportTicket ticket, SupportPrincipal principal) {
        if (ticket == null) {
            throw new SupportAccessDeniedException("Тикет не найден");
        }
        SupportPrincipal effective = principal == null ? installPrincipal() : principal;
        if (!effective.scope().equals(ticket.scope())) {
            throw new SupportAccessDeniedException("Тикет вне доступного пространства");
        }
        if (properties.sessionIsolation() && !effective.owner().equals(ticket.owner())) {
            throw new SupportAccessDeniedException("Тикет принадлежит другой сессии");
        }
    }

    /** Returns (creating if needed) the session CSRF token. */
    public String csrfToken(HttpSession session) {
        Object existing = session.getAttribute(CSRF_SESSION_ATTRIBUTE);
        if (existing instanceof String token && !token.isBlank()) {
            return token;
        }
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        session.setAttribute(CSRF_SESSION_ATTRIBUTE, token);
        return token;
    }

    /** True when the request carries a valid CSRF token for its session. */
    public boolean csrfValid(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object expected = session.getAttribute(CSRF_SESSION_ATTRIBUTE);
        if (!(expected instanceof String expectedToken) || expectedToken.isBlank()) {
            return false;
        }
        String supplied = request.getHeader(CSRF_HEADER);
        if (supplied == null) {
            supplied = request.getParameter(CSRF_PARAMETER);
        }
        return constantTimeEquals(expectedToken, supplied);
    }

    /** Throws when a state-changing request lacks a valid CSRF token. */
    public void requireCsrf(HttpServletRequest request) {
        if (!csrfValid(request)) {
            throw new SupportAccessDeniedException(
                    "Недействительный CSRF-токен. Обновите страницу и повторите.");
        }
    }

    static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
