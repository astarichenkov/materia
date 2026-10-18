package com.lectureprocessor.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Replaces the Whitelabel error page with a safe, readable error view for the
 * UI and a small JSON body for API paths. Stack traces stay in the server log
 * and are correlated by {@code X-Correlation-Id}.
 */
@Controller
public class FriendlyErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(FriendlyErrorController.class);

    @RequestMapping("/error")
    public Object error(HttpServletRequest request, Model model) {
        int statusCode = statusCode(request);
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String path = value(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        String correlationId = value(request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        String message = message(status);
        if (status.is5xxServerError()) {
            Throwable error = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            log.error("Unhandled request error [{}] {} {}: {}", correlationId, statusCode, path,
                    error == null ? "no exception" : error.toString(), error);
        }

        if (isApi(request, path)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("timestamp", Instant.now().toString());
            body.put("status", statusCode);
            body.put("error", status.getReasonPhrase());
            body.put("message", message);
            body.put("path", path);
            body.put("correlationId", correlationId);
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
        }

        model.addAttribute("status", statusCode);
        model.addAttribute("error", status.getReasonPhrase());
        model.addAttribute("message", message);
        model.addAttribute("path", path);
        model.addAttribute("correlationId", correlationId);
        return "error";
    }

    private static int statusCode(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (value instanceof Integer number) {
            return number;
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    private static boolean isApi(HttpServletRequest request, String path) {
        String accept = request.getHeader("Accept");
        return path.startsWith("/api/") || path.startsWith("/mcp")
                || (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)
                        && !accept.contains(MediaType.TEXT_HTML_VALUE));
    }

    private static String message(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "Страница или ресурс не найдены. Проверьте адрес и вернитесь в библиотеку.";
            case BAD_REQUEST -> "Запрос содержит некорректные данные.";
            case FORBIDDEN -> "Доступ запрещён. Для настроек нужен localhost-сеанс или admin-токен.";
            case UNAUTHORIZED -> "Требуется авторизация.";
            case METHOD_NOT_ALLOWED -> "Действие недоступно для этого HTTP-метода.";
            case CONFLICT -> "Данные уже изменились. Обновите страницу и повторите действие.";
            case SERVICE_UNAVAILABLE, BAD_GATEWAY, GATEWAY_TIMEOUT ->
                    "Внешняя зависимость временно недоступна. Проверьте /diagnostics.";
            default -> status.is5xxServerError()
                    ? "Внутренняя ошибка приложения. Данные сохранены; подробности — в логе по correlation id."
                    : status.getReasonPhrase();
        };
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }
}
