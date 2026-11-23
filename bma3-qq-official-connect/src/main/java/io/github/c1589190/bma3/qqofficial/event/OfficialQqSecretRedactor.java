package io.github.c1589190.bma3.qqofficial.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defensive redaction for structured card/nested payloads and URLs.
 *
 * <p>Only explicitly modeled fields ever reach segments, but cards and forward/nested payloads
 * are open-ended upstream JSON. This utility recursively removes credential-shaped keys before
 * any such payload is serialized into {@link io.github.c1589190.bma3.core.message.Segment#data()}.</p>
 *
 * <p>The key list is explicit rather than substring-based so ordinary facts such as
 * {@code tokenizer} or {@code token_type} are not misclassified as credentials. URLs used as
 * structured fields must be HTTPS, must not carry userinfo, and have credential-shaped query
 * keys removed even when those keys are percent-encoded.</p>
 */
public final class OfficialQqSecretRedactor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REDACTED = "[REDACTED]";
    private static final String BLOCKED_URL = "[BLOCKED_URL]";
    private static final String OVERSIZED_JSON = "[OVERSIZED_JSON]";
    private static final int MAX_SAFE_JSON_CHARS = 8192;
    private static final int MAX_NODE_SCAN = 2048;
    private static final int MAX_DEPTH = 12;

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "token",
            "authtoken",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "bearertoken",
            "authorization",
            "secret",
            "appsecret",
            "clientsecret",
            "botsecret",
            "authsecret",
            "password",
            "passwd",
            "cookie",
            "cookies",
            "apikey");

    private static final Pattern INLINE_ASSIGNMENT = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_-])"
                    + "(token|auth[_-]?token|access[_-]?token|refresh[_-]?token"
                    + "|id[_-]?token|bearer[_-]?token|authorization|app[_-]?secret"
                    + "|client[_-]?secret|bot[_-]?secret|auth[_-]?secret|secret"
                    + "|password|passwd|api[_-]?key)"
                    + "(\\s*[=:]\\s*)"
                    + "(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^\\s&,;\"']+)");

    private static final Pattern URL_IN_TEXT = Pattern.compile("(?i)https?://[^\\s\"'<>]+");

    private OfficialQqSecretRedactor() {
    }

    /**
     * True when a JSON field name denotes a credential. Comparison ignores punctuation and case,
     * but requires an explicit credential name instead of a broad substring match.
     */
    public static boolean sensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE_KEYS.contains(normalized);
    }

    /**
     * Returns a deep redacted copy; the input tree is never mutated. Textual values are also
     * screened for embedded URLs so a URL hidden inside a JSON string cannot keep credentials.
     */
    public static JsonNode redact(JsonNode node) {
        return redact(node, 0);
    }

    private static JsonNode redact(JsonNode node, int depth) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (depth > MAX_DEPTH) {
            return TextNode.valueOf(REDACTED);
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (sensitiveKey(field.getKey())) {
                    // Drop the key entirely: even the credential-shaped name must not be
                    // persisted into a card's structured segment payload.
                    continue;
                }
                copy.set(field.getKey(), redact(field.getValue(), depth + 1));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : node) {
                copy.add(redact(element, depth + 1));
            }
            return copy;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(sanitizeStructuredText(node.asText("")));
        }
        return node.deepCopy();
    }

    /**
     * Redacts inline credential assignments while leaving ordinary prose, including URLs and
     * tokenizer/token_type facts, unchanged.
     */
    public static String redactText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return INLINE_ASSIGNMENT.matcher(value).replaceAll(REDACTED);
    }

    /**
     * Removes credential-shaped query parameters and rejects unsafe structured URLs.
     *
     * <p>Accepts only absolute HTTPS URLs with a host and no userinfo. Percent-encoded query keys
     * are decoded repeatedly before classification, so {@code auth%5Ftoken} is treated the same
     * as {@code auth_token}. Non-sensitive query parameters such as {@code rkey} are preserved.</p>
     */
    public static String sanitizeUrl(String value) {
        if (value == null || value.isBlank() || containsUnsafeCharacters(value)) {
            return "";
        }
        URI uri;
        try {
            uri = new URI(value.strip());
        } catch (Exception malformed) {
            return "";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null
                || uri.getRawAuthority() == null
                || uri.getRawAuthority().isBlank()
                || uri.getHost() == null
                || uri.getHost().isBlank()) {
            return "";
        }
        String query = filterPairs(uri.getRawQuery());
        String fragment = filterPairs(uri.getRawFragment());
        StringBuilder safe = new StringBuilder("https://")
                .append(uri.getRawAuthority())
                .append(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (!query.isEmpty()) {
            safe.append('?').append(query);
        }
        if (!fragment.isEmpty()) {
            safe.append('#').append(fragment);
        }
        return redactText(safe.toString());
    }

    /** Serializes a redacted, length-bounded JSON value for a structured segment payload. */
    public static String safeJson(JsonNode node, int maxChars) {
        int limit = maxChars <= 0 ? MAX_SAFE_JSON_CHARS : maxChars;
        int[] remainingChars = {Math.max(limit * 2, 1024)};
        if (node == null
                || exceedsScanBudget(
                        node, 0, new int[] {MAX_NODE_SCAN}, remainingChars)) {
            return OVERSIZED_JSON;
        }
        LimitedWriter writer = new LimitedWriter(limit);
        try {
            MAPPER.writeValue(writer, redact(node));
            return writer.value();
        } catch (Exception ignored) {
            return OVERSIZED_JSON;
        }
    }

    public static String safeJson(JsonNode node) {
        return safeJson(node, MAX_SAFE_JSON_CHARS);
    }

    private static String sanitizeStructuredText(String value) {
        String redacted = redactText(value);
        Matcher matcher = URL_IN_TEXT.matcher(redacted);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String safeUrl = sanitizeUrl(matcher.group());
            String replacement = safeUrl.isBlank() ? BLOCKED_URL : safeUrl;
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String filterPairs(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String pair : raw.split("[&;]")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equalsAt = pair.indexOf('=');
            String rawKey = equalsAt < 0 ? pair : pair.substring(0, equalsAt);
            if (isSensitiveEncodedKey(rawKey)) {
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append('&');
            }
            kept.append(redactText(pair));
        }
        return kept.toString();
    }

    private static boolean isSensitiveEncodedKey(String rawKey) {
        String decoded = rawKey;
        for (int round = 0; round < 3; round++) {
            try {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            } catch (IllegalArgumentException malformed) {
                break;
            }
        }
        return sensitiveKey(decoded);
    }

    private static boolean containsUnsafeCharacters(String value) {
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean exceedsScanBudget(
            JsonNode node, int depth, int[] remainingNodes, int[] remainingChars) {
        if (remainingNodes[0]-- <= 0 || depth > MAX_DEPTH) {
            return true;
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            remainingChars[0] -= node.textValue().length() + 2;
            return remainingChars[0] < 0;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                remainingChars[0] -= field.getKey().length() + 4;
                if (remainingChars[0] < 0
                        || exceedsScanBudget(
                                field.getValue(), depth + 1, remainingNodes, remainingChars)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                remainingChars[0] -= 1;
                if (remainingChars[0] < 0
                        || exceedsScanBudget(child, depth + 1, remainingNodes, remainingChars)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class LimitedWriter extends Writer {
        private final StringWriter delegate = new StringWriter();
        private final int limit;

        private LimitedWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            if (delegate.getBuffer().length() + length > limit) {
                throw new IOException("safe-json-limit");
            }
            delegate.write(buffer, offset, length);
        }

        @Override
        public void write(String value, int offset, int length) throws IOException {
            if (delegate.getBuffer().length() + length > limit) {
                throw new IOException("safe-json-limit");
            }
            delegate.write(value, offset, length);
        }

        @Override
        public void flush() {
            // In-memory writer has nothing to flush.
        }

        @Override
        public void close() {
            // In-memory writer has nothing to close.
        }

        private String value() {
            return delegate.toString();
        }
    }
}
