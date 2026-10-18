package com.lectureprocessor.editor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * CSRF and scope guard for the lecture editor.
 *
 * <p>The application is a single-installation, local-trust deployment: every
 * editor operation is scoped to one lecture id that is resolved and verified
 * against the database before any mutation, so knowing another id is not a way
 * to reach a foreign workspace. State-changing requests additionally require a
 * synchronizer CSRF token bound to the browser session.</p>
 */
@Service
public class EditorAccessService {

    public static final String CSRF_SESSION_ATTRIBUTE = "lp.editor.csrf";
    public static final String CSRF_HEADER = "X-Editor-Csrf";
    public static final String CSRF_PARAMETER = "_csrf";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 24;

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
        return expectedToken.equals(supplied);
    }

    /** Throws unless the request carries a valid editor CSRF token. */
    public void requireCsrf(HttpServletRequest request) {
        if (!csrfValid(request)) {
            throw EditorException.forbidden("Недействительный CSRF-токен. Обновите страницу и повторите.");
        }
    }

    /** Constant-time token comparison helper (used by tests and wrappers). */
    static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
