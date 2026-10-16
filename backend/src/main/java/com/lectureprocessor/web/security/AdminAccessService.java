package com.lectureprocessor.web.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Access control and CSRF protection for the provider/credential administration
 * surface. The application has no full user system yet, so credential
 * management is protected as follows:
 *
 * <ul>
 *   <li>when {@code app.security.admin-token} is blank, requests are accepted
 *       only from loopback addresses (documented limitation);</li>
 *   <li>when a token is configured it must be supplied (login form, session or
 *       {@code X-Admin-Token} header) — recommended for any network exposure;</li>
 *   <li>state-changing requests made with a browser session require a CSRF
 *       synchronizer token, so a malicious third-party page cannot save or
 *       delete a credential.</li>
 * </ul>
 */
@Service
public class AdminAccessService {

    public static final String CSRF_SESSION_ATTRIBUTE = "lp.csrf";
    public static final String ADMIN_SESSION_ATTRIBUTE = "lp.admin";
    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";
    public static final String CSRF_PARAMETER = "_csrf";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 24;
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost");

    private final AppProperties properties;

    public AdminAccessService(AppProperties properties) {
        this.properties = properties;
    }

    public boolean tokenConfigured() {
        return adminToken() != null;
    }

    private String adminToken() {
        AppProperties.Security security = properties.security();
        String token = security == null ? null : security.adminToken();
        return token == null || token.isBlank() ? null : token;
    }

    /** True when the request may access the administration surface. */
    public boolean isAuthorized(HttpServletRequest request) {
        if (hasAuthenticatedSession(request)) {
            return true;
        }
        String configured = adminToken();
        if (configured == null) {
            return isLoopback(request.getRemoteAddr());
        }
        if (constantTimeEquals(configured, request.getHeader(ADMIN_TOKEN_HEADER))) {
            return true;
        }
        // Do not accept the admin token as a query/form parameter here: it would
        // leak into URLs and access logs. The login endpoint handles the form
        // field separately and establishes a session.
        return false;
    }

    /** Validates a login attempt and marks the session authenticated on success. */
    public boolean login(HttpServletRequest request, String candidate) {
        String configured = adminToken();
        if (configured == null) {
            // No token configured: loopback is already authorized.
            if (isLoopback(request.getRemoteAddr())) {
                HttpSession session = request.getSession(true);
                session.setAttribute(ADMIN_SESSION_ATTRIBUTE, Boolean.TRUE);
                return true;
            }
            return false;
        }
        if (!constantTimeEquals(configured, candidate)) {
            return false;
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(ADMIN_SESSION_ATTRIBUTE, Boolean.TRUE);
        return true;
    }

    public void logout(HttpSession session) {
        if (session != null) {
            session.removeAttribute(ADMIN_SESSION_ATTRIBUTE);
        }
    }

    private boolean hasAuthenticatedSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(ADMIN_SESSION_ATTRIBUTE));
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

    /**
     * CSRF is required for browser-session requests. Requests authenticated with
     * the {@code X-Admin-Token} header are exempt (they are not cookie-based and
     * cannot be triggered by a third-party page).
     */
    public boolean csrfRequired(HttpServletRequest request) {
        if (constantTimeEquals(adminToken(), request.getHeader(ADMIN_TOKEN_HEADER))) {
            return false;
        }
        String method = request.getMethod();
        return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)
                && !"OPTIONS".equalsIgnoreCase(method);
    }

    public static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null) {
            return false;
        }
        String normalized = remoteAddr.startsWith("[") && remoteAddr.endsWith("]")
                ? remoteAddr.substring(1, remoteAddr.length() - 1) : remoteAddr;
        return LOOPBACK.contains(normalized) || normalized.startsWith("127.");
    }

    static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
