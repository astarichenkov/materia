package com.lectureprocessor.web.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.lectureprocessor.config.AppProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Guards the provider/credential administration surface.
 *
 * <p>Protected prefixes: {@code /settings}, {@code /api/providers} and
 * {@code /api/llm/tasks}. Requests must either come from loopback (when no admin
 * token is configured), carry {@code X-Admin-Token}, or be an authenticated
 * admin session. State-changing requests additionally require a CSRF token.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AdminSecurityFilter extends OncePerRequestFilter {

    private static final List<String> PROTECTED_PREFIXES = List.of(
            "/settings", "/api/providers", "/code-review", "/api/code-review",
            "/file-agent", "/api/file-agent", "/diagnostics", "/api/diagnostics");
    private static final List<String> PUBLIC_PATHS = List.of("/settings/login",
            "/api/code-review/webhook/gitea");

    private final AdminAccessService access;

    public AdminSecurityFilter(AppProperties properties) {
        // Self-contained on purpose: @WebMvcTest slices include web filters but
        // not @Service beans, so the filter must not require the service bean.
        this.access = new AdminAccessService(properties);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!isProtected(path) || PUBLIC_PATHS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!access.isAuthorized(request)) {
            if (!request.getRequestURI().startsWith("/api/")
                    && "GET".equalsIgnoreCase(request.getMethod())) {
                response.sendRedirect("/settings/login");
                return;
            }
            reject(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "Доступ к настройкам провайдеров запрещён. "
                            + "Настройте app.security.admin-token или обращайтесь с localhost.");
            return;
        }
        if (access.csrfRequired(request) && !access.csrfValid(request)) {
            reject(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "Недействительный CSRF-токен. Обновите страницу настроек и повторите.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isProtected(String path) {
        return PROTECTED_PREFIXES.stream().anyMatch(prefix ->
                path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix));
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response, int status,
            String message) throws IOException {
        response.setStatus(status);
        boolean api = request.getRequestURI().startsWith("/api/");
        if (api) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
        } else {
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(message);
        }
    }
}
