package io.github.c1589190.bma3.app.official;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.inbox.core.InboxEntry;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqSecretRedactor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Defensive projection of official segments into the board and Inbox.
 *
 * <p>Board segments are allowed to keep structured media URLs and opaque rkey values for later
 * detail tools. Inbox only receives plain text plus fixed/short markers; segment data is never
 * rendered there. Credential-shaped keys are dropped at this boundary, URLs are sanitized, and
 * the official envelope's {@code auth_token} can therefore never become a persisted row.</p>
 */
final class OfficialQqSegments {

    private static final int MAX_SEGMENTS = 512;
    private static final int MAX_SEGMENT_TEXT_CHARS = 64_000;
    private static final int MAX_TOTAL_TEXT_CHARS = 128_000;
    private static final int MAX_DATA_KEYS = 64;
    private static final int MAX_MARKER_CODE_POINTS = 160;
    private static final int MAX_DATA_VALUE_CHARS = 16_384;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SAFE_TYPE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern SAFE_DATA_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern HTTP_URL = Pattern.compile("(?i)https?://[^\\s\"'<>]+");
    private static final Set<String> URL_KEYS = Set.of(
            "url", "jump_url", "qqdocurl", "jumpurl", "avatar", "image_url", "media_url");

    private OfficialQqSegments() {
    }

    /**
     * Returns an immutable, sanitized copy; the input event/segments are never mutated.
     *
     * <p>This boundary is a message-fact guard as well as a redactor. A direct caller providing too
     * many segments, oversized text/structured values, or malformed segment shapes gets a fixed
     * {@link OfficialIngestException} before any board write. Silently dropping a segment or
     * truncating a structured value would make the board an incomplete fact source.</p>
     */
    static List<Segment> sanitize(List<Segment> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        if (input.size() > MAX_SEGMENTS) {
            throw invalidSegments();
        }
        List<Segment> out = new ArrayList<>(input.size());
        long totalTextChars = 0L;
        for (Segment segment : input) {
            if (segment == null) {
                throw invalidSegments();
            }
            String type = requireSafeType(segment.type());
            if (segment.text().length() > MAX_SEGMENT_TEXT_CHARS) {
                throw invalidSegments();
            }
            String text = OfficialQqSecretRedactor.redactText(segment.text());
            totalTextChars += text.length();
            if (totalTextChars > MAX_TOTAL_TEXT_CHARS) {
                throw invalidSegments();
            }
            Map<String, String> data = safeData(segment.data());
            out.add(new Segment(type, text, data));
        }
        return List.copyOf(out);
    }

    /** Text body only; structured URLs/media/reference payloads remain in segments. */
    static String bodyText(List<Segment> segments) {
        StringBuilder body = new StringBuilder();
        if (segments == null) {
            return "";
        }
        for (Segment segment : segments) {
            if (segment == null || !"text".equals(segment.type())
                    || segment.text().isBlank()) {
                continue;
            }
            if (!body.isEmpty()) {
                body.append('\n');
            }
            body.append(segment.text());
        }
        return body.toString();
    }

    /** Builds the bounded Inbox preview: text body plus non-URL media/relation markers. */
    static InboxEntry entry(long localSeq, Instant at, String sender, List<Segment> segments) {
        StringBuilder body = new StringBuilder();
        List<String> markers = new ArrayList<>();
        if (segments != null) {
            for (Segment segment : segments) {
                if (segment == null) {
                    continue;
                }
                String type = segment.type();
                if ("text".equals(type)) {
                    if (!segment.text().isBlank()) {
                        if (!body.isEmpty()) {
                            body.append('\n');
                        }
                        body.append(segment.text());
                    }
                    continue;
                }
                switch (type) {
                    case "at" -> markers.add(marker(segment.text(), "[@]"));
                    case "quote" -> markers.add("[引用]");
                    case "image" -> markers.add("[图片]");
                    case "video" -> markers.add("[视频]");
                    case "record" -> markers.add("[语音]");
                    case "file" -> markers.add("[文件]");
                    case "forward" -> markers.add("[合并转发]");
                    case "card" -> markers.add(marker(segment.text(), "[卡片]"));
                    default -> markers.add("[" + displayType(type) + "]");
                }
            }
        }
        return new InboxEntry(localSeq, at, sender == null ? "" : sender,
                body.toString(), markers);
    }

    /** Stable SHA-256 key over raw source + exact string message id. */
    static String requestKey(String rawSource, String messageId) {
        String material = "qq-official-inbox-v1\u0000" + rawSource + "\u0000" + messageId;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(value & 0x0f, 16));
            }
            return "qqofficial-inbox:" + hex;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private static Map<String, String> safeData(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (raw.size() > MAX_DATA_KEYS) {
            throw invalidSegments();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !SAFE_DATA_KEY.matcher(key).matches()) {
                throw invalidSegments();
            }
            // Credential-shaped keys are deliberately not facts. Decoders should already remove
            // them; this boundary remains defense-in-depth and keeps auth_token out of the board.
            if (OfficialQqSecretRedactor.sensitiveKey(key)) {
                continue;
            }
            if (value == null || value.isBlank()) {
                continue;
            }
            String safe = safeStructuredValue(key, value);
            if (safe.isBlank()) {
                throw invalidSegments();
            }
            out.put(key, safe);
        }
        return Map.copyOf(out);
    }

    private static String safeStructuredValue(String key, String value) {
        if (value.length() > MAX_DATA_VALUE_CHARS) {
            throw invalidSegments();
        }
        String redacted = OfficialQqSecretRedactor.redactText(value);
        if (redacted.length() > MAX_DATA_VALUE_CHARS) {
            throw invalidSegments();
        }
        String lowerKey = key.toLowerCase(java.util.Locale.ROOT);
        if (URL_KEYS.contains(lowerKey) || lowerKey.endsWith("_url")) {
            String url = OfficialQqSecretRedactor.sanitizeUrl(redacted);
            if (url.isBlank()) {
                throw invalidSegments();
            }
            return url;
        }
        if ("file_info".equals(key) || "raw".equals(key)) {
            return sanitizeJson(redacted);
        }
        requireStructuredText(redacted);
        return redacted;
    }

    private static String sanitizeJson(String value) {
        if (value == null || value.isBlank()) {
            throw invalidSegments();
        }
        try {
            JsonNode node = JSON.readTree(value);
            if (node == null || node.isNull() || node.isMissingNode()) {
                throw invalidSegments();
            }
            String safe = OfficialQqSecretRedactor.safeJson(node, MAX_DATA_VALUE_CHARS);
            if (safe.isBlank() || "[OVERSIZED_JSON]".equals(safe)) {
                throw invalidSegments();
            }
            return safe;
        } catch (OfficialIngestException failure) {
            throw failure;
        } catch (Exception notJson) {
            throw invalidSegments();
        }
    }

    private static String requireSafeType(String value) {
        if (value == null || !SAFE_TYPE.matcher(value).matches()) {
            throw invalidSegments();
        }
        return value;
    }

    private static void requireStructuredText(String value) {
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw invalidSegments();
            }
            index += Character.charCount(codePoint);
        }
    }

    private static String displayType(String value) {
        return value != null && SAFE_TYPE.matcher(value).matches() ? value : "unknown";
    }

    private static OfficialIngestException invalidSegments() {
        return new OfficialIngestException(OfficialIngestException.Code.INVALID_SEGMENT,
                "官方消息 segments 非法或超过安全上限");
    }

    private static String marker(String candidate, String fallback) {
        String value = OfficialQqSecretRedactor.redactText(candidate);
        value = HTTP_URL.matcher(value).replaceAll("[链接]");
        value = stripUnsafeControl(value, MAX_MARKER_CODE_POINTS).strip();
        return value.isBlank() ? fallback : value;
    }

    private static String stripUnsafeControl(String value, int maxCodePoints) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), maxCodePoints));
        int codePoints = 0;
        for (int index = 0; index < value.length() && codePoints < maxCodePoints; ) {
            int codePoint = value.codePointAt(index);
            int type = Character.getType(codePoint);
            boolean unsafe = Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR;
            if (!unsafe) {
                out.appendCodePoint(codePoint);
                codePoints++;
            }
            index += Character.charCount(codePoint);
        }
        return out.toString();
    }
}
