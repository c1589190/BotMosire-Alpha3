package io.mosire.bma3.app.logging;

import java.util.regex.Pattern;

/** Best-effort redaction for logs and user-visible errors. */
public final class Redactor {

    private static final String MASK = "[REDACTED]";
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern COOKIE = Pattern.compile(
            "(?i)(Cookie:\\s*)[^\\r\\n]+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)((?:password|passwd|token|secret|api[_-]?key)\\s*[=:]\\s*)[^\\s,;]+");

    private Redactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String redacted = BEARER.matcher(text).replaceAll("$1" + MASK);
        redacted = COOKIE.matcher(redacted).replaceAll("$1" + MASK);
        redacted = SECRET.matcher(redacted).replaceAll("$1" + MASK);
        return redacted;
    }
}
