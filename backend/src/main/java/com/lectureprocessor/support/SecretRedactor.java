package com.lectureprocessor.support;

import java.util.regex.Pattern;

/**
 * Redacts common secret shapes from tool output before it is stored, returned to
 * the LLM or rendered in the UI. Defense in depth: tools should never return
 * secrets, but this guarantees the support surface never leaks them either.
 */
public final class SecretRedactor {

    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?\\S+");
    private static final Pattern ASSIGNED_SECRET = Pattern.compile(
            "(?i)((?:api[_-]?key|secret|token|password|passwd|pwd)\\s*[:=]\\s*)[^\\s,;\"']+");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]+");
    private static final Pattern OPENAI_STYLE = Pattern.compile("\\bsk-[A-Za-z0-9]{16,}\\b");

    private SecretRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = AUTHORIZATION.matcher(text).replaceAll("$1[REDACTED]");
        result = ASSIGNED_SECRET.matcher(result).replaceAll("$1[REDACTED]");
        result = BEARER.matcher(result).replaceAll("Bearer [REDACTED]");
        result = OPENAI_STYLE.matcher(result).replaceAll("[REDACTED]");
        return result;
    }
}
