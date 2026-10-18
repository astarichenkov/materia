package com.lectureprocessor.web;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Adds a correlation id to every request so a user-facing error can be matched
 * with the full server-side stack trace. The id is echoed in the
 * {@code X-Correlation-Id} response header and in the error page.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = "correlationId";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String existing = (String) request.getAttribute(ATTRIBUTE);
        String incoming = request.getHeader(HEADER);
        String correlationId = valid(existing) ? existing
                : valid(incoming) ? incoming : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static boolean valid(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{8,80}");
    }
}
