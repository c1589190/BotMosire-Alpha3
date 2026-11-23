package io.github.c1589190.bma3.qqconnect.onebot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.bma3.core.message.Segment;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decodes OneBot 11 message events into the small BMA3 model. */
public final class OneBot11Codec {

    /** Structured payload keys kept per segment type; everything else is dropped. */
    private static final Map<String, List<String>> DATA_KEYS = Map.ofEntries(
            Map.entry("text", List.of("text")),
            Map.entry("at", List.of("qq", "name")),
            Map.entry("reply", List.of("id")),
            Map.entry("image", List.of("file", "url", "sub_type", "summary", "file_size",
                    "width", "height")),
            Map.entry("record", List.of("file", "url")),
            Map.entry("video", List.of("file", "url", "name")),
            Map.entry("file", List.of("file", "url", "name", "size")),
            Map.entry("forward", List.of("id", "count", "title")),
            Map.entry("face", List.of("id")),
            Map.entry("mface", List.of("url", "summary", "emoji_id")),
            Map.entry("json", List.of("data", "text")),
            Map.entry("xml", List.of("data", "text")));

    private static final int MAX_CARD_RAW_CHARS = 8192;

    /** Segment types whose current parser understands the complete content-bearing payload. */
    private static final Set<String> COMPLETE_SEGMENT_TYPES = Set.of(
            "text", "at", "reply", "image", "record", "video", "file", "forward",
            "face", "mface", "json", "xml");
    private static final Pattern GROUP_INVITE = Pattern.compile(
            "mqqapi://group/invite_join[^\\s\"'<>]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_TITLE = Pattern.compile("(?is)<title>(.*?)</title>");
    private static final Pattern XML_SUMMARY = Pattern.compile("(?is)<summary>(.*?)</summary>");
    private static final Pattern XML_URL = Pattern.compile("(?is)url=\"([^\"]{1,2000})\"");

    private final ObjectMapper mapper = new ObjectMapper();

    public Optional<OneBotEvent> decode(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !"message".equals(root.path("post_type").asText())) {
                return Optional.empty();
            }
            long groupId = root.path("group_id").asLong(0L);
            JsonNode messageNode = root.get("message");
            List<Segment> segments = parseSegments(messageNode);
            boolean contentComplete = completeSegmentArray(messageNode);
            String replyTo = segments.stream()
                    .filter(segment -> "reply".equals(segment.type()))
                    .map(segment -> segment.data("id"))
                    .findFirst()
                    .orElse("");
            JsonNode sender = root.path("sender");
            Timestamp timestamp = parseTimestamp(root.get("time"));
            return Optional.of(new OneBotEvent(
                    root.path("post_type").asText(),
                    root.path("message_type").asText(),
                    root.path("sub_type").asText(""),
                    root.path("self_id").asLong(),
                    root.path("user_id").asLong(),
                    groupId,
                    root.path("message_id").asLong(),
                    timestamp.at(),
                    sender.path("nickname").asText(""),
                    segments,
                    replyTo,
                    sender.path("card").asText(""),
                    sender.path("role").asText(""),
                    timestamp.fallback(),
                    contentComplete));
        } catch (Exception failure) {
            throw new IllegalArgumentException("OneBot 事件解析失败（原因类型 "
                    + failure.getClass().getSimpleName() + "）");
        }
    }

    /**
     * A timestamp is trustworthy only when it is a positive integral JSON number convertible to
     * a real {@link Instant}. Missing, textual, fractional, non-positive, or out-of-range values
     * fall back to server receive time and are explicitly marked unreliable.
     */
    private static Timestamp parseTimestamp(JsonNode timeNode) {
        Instant receivedAt = Instant.now();
        if (timeNode == null
                || !timeNode.isIntegralNumber()
                || !timeNode.canConvertToLong()) {
            return new Timestamp(receivedAt, true);
        }
        long seconds = timeNode.longValue();
        if (seconds <= 0L) {
            return new Timestamp(receivedAt, true);
        }
        try {
            return new Timestamp(Instant.ofEpochSecond(seconds), false);
        } catch (DateTimeException impossible) {
            return new Timestamp(receivedAt, true);
        }
    }

    /**
     * True only for an array whose every element is a fully understood OneBot segment shape.
     * A string (including raw CQ code), missing/object message, scalar element, missing type/data,
     * unknown type, or a known type without its identifying payload is conservatively incomplete.
     */
    private static boolean completeSegmentArray(JsonNode message) {
        if (message == null || !message.isArray()) {
            return false;
        }
        for (JsonNode segment : message) {
            if (!segment.isObject()) {
                return false;
            }
            JsonNode typeNode = segment.get("type");
            JsonNode data = segment.get("data");
            if (typeNode == null || !typeNode.isTextual() || typeNode.asText().isBlank()
                    || data == null || !data.isObject()) {
                return false;
            }
            String type = typeNode.asText();
            if (!COMPLETE_SEGMENT_TYPES.contains(type) || !completePayload(type, data)) {
                return false;
            }
        }
        return true;
    }

    private static boolean completePayload(String type, JsonNode data) {
        return switch (type) {
            case "text" -> data.get("text") != null && data.get("text").isTextual();
            case "at" -> present(data, "qq");
            case "reply" -> present(data, "id");
            case "image", "record", "video", "file" ->
                    present(data, "file") || present(data, "url") || present(data, "path");
            case "forward" -> present(data, "id");
            case "face" -> present(data, "id");
            case "mface" -> present(data, "url") || present(data, "summary")
                    || present(data, "emoji_id");
            case "json", "xml" -> data.get("data") != null && data.get("data").isTextual();
            default -> false;
        };
    }

    private static boolean present(JsonNode data, String field) {
        JsonNode value = data.get(field);
        return value != null && !value.isNull() && value.isValueNode()
                && !value.asText("").isBlank();
    }

    private record Timestamp(Instant at, boolean fallback) {
    }

    /**
     * Parses a OneBot segment array (or a plain string) into BMA3 segments. Public so the forward
     * viewer can decode the {@code get_forward_msg} nodes with exactly the same rules.
     */
    public static List<Segment> parseSegments(JsonNode message) {
        if (message == null || message.isMissingNode() || message.isNull()) {
            return List.of();
        }
        if (message.isTextual()) {
            return List.of(new Segment("text", message.asText()));
        }
        if (!message.isArray()) {
            return List.of();
        }
        List<Segment> segments = new ArrayList<>();
        for (JsonNode segment : message) {
            String type = segment.path("type").asText("text");
            JsonNode data = segment.path("data");
            String text = switch (type) {
                case "text" -> data.path("text").asText("");
                case "reply" -> data.path("id").asText("");
                case "at" -> data.path("qq").asText("");
                case "image" -> "[图片]";
                case "file" -> "[文件]";
                case "record" -> "[语音]";
                case "video" -> "[视频]";
                case "forward" -> "[合并转发]";
                case "json", "xml" -> cardText(type, data);
                default -> data.path("text").asText("[" + type + "]");
            };
            segments.add(new Segment(type, text, payload(data, type)));
        }
        return List.copyOf(segments);
    }

    /**
     * Keeps only the documented payload keys for a segment type. Long-lived CDN URLs carry an
     * {@code rkey} and expire; they are stored because an on-demand media tool needs them, but
     * they never reach {@code chat_message.text} or the Inbox panel.
     */
    private static Map<String, String> payload(JsonNode data, String type) {
        if (data == null || !data.isObject()) {
            return Map.of();
        }
        List<String> keys = DATA_KEYS.get(type);
        if (keys == null) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : keys) {
            JsonNode value = data.path(key);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            String text = value.isValueNode() ? value.asText("") : "";
            if (!text.isBlank()) {
                values.put(key, text);
            }
        }
        if ("json".equals(type) || "xml".equals(type)) {
            cardFields(type, data, values);
        }
        return values;
    }

    /** Adds a bounded raw card + the fields a reader needs, and never stores the invite ticket. */
    private static void cardFields(String type, JsonNode data, Map<String, String> values) {
        String raw = data.path("data").asText("");
        if (raw.isBlank()) {
            raw = data.isTextual() ? data.asText("") : "";
        }
        JsonNode card = null;
        if ("json".equals(type) && !raw.isBlank()) {
            try {
                card = new ObjectMapper().readTree(raw);
            } catch (Exception ignored) {
                card = null;
            }
        }
        if ("json".equals(type) && card != null) {
            putFirst(values, "title", text(card, "title"), text(card, "prompt"));
            putFirst(values, "desc", text(card, "desc"), text(card, "summary"));
            String bizsrc = text(card, "bizsrc");
            if (!bizsrc.isBlank()) {
                values.put("bizsrc", bizsrc);
            }
            String url = firstNonBlank(text(card, "qqdocurl"), text(card, "jumpUrl"),
                    text(card, "url"));
            if (url.isBlank()) {
                JsonNode meta = card.path("meta");
                if (meta.isObject()) {
                    for (Map.Entry<String, JsonNode> field : meta.properties()) {
                        JsonNode value = field.getValue();
                        if (value != null && value.isObject()) {
                            url = firstNonBlank(url, text(value, "qqdocurl"),
                                    text(value, "jumpUrl"), text(value, "url"));
                            putFirst(values, "title", text(value, "title"));
                            putFirst(values, "desc", text(value, "desc"),
                                    text(value, "summary"));
                        }
                    }
                }
            }
            if (!url.isBlank()) {
                values.put("url", url);
            }
            inviteFields(raw, values);
        }
        if ("xml".equals(type) && !raw.isBlank()) {
            putFirst(values, "title", firstGroup(XML_TITLE, raw));
            putFirst(values, "desc", firstGroup(XML_SUMMARY, raw));
            String url = firstGroup(XML_URL, raw);
            if (!url.isBlank()) {
                values.put("url", url);
            }
            inviteFields(raw, values);
        }
        if (!raw.isBlank()) {
            values.put("raw", cap(GROUP_INVITE.matcher(raw).replaceAll("[群邀请链接已移除]"),
                    MAX_CARD_RAW_CHARS));
        }
    }

    /** Extracts group id + notification sequence from a QQ group-invite card. */
    private static void inviteFields(String raw, Map<String, String> values) {
        Matcher matcher = GROUP_INVITE.matcher(raw);
        if (!matcher.find()) {
            return;
        }
        String link = matcher.group();
        int query = link.indexOf('?');
        if (query < 0) {
            return;
        }
        for (String pair : link.substring(query + 1).split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = pair.substring(0, separator);
            String value = URLDecoder.decode(pair.substring(separator + 1),
                    StandardCharsets.UTF_8);
            if (value.isBlank()) {
                continue;
            }
            switch (key) {
                case "groupcode", "group_code", "groupCode", "gc" ->
                        values.putIfAbsent("invite_group", value);
                case "msgseq", "msgSeq", "sequence" ->
                        values.putIfAbsent("invite_seq", value);
                default -> {
                    // Never persist the invite ticket/other query fields.
                }
            }
        }
        // The mqqapi link itself is a ticket; drop it only when it is the card's URL.
        String url = values.get("url");
        if (url != null && url.toLowerCase(java.util.Locale.ROOT)
                .contains("mqqapi://group/invite_join")) {
            values.remove("url");
        }
    }

    private static String cardText(String type, JsonNode data) {
        String raw = data.path("data").asText("");
        String title = "";
        if ("json".equals(type)) {
            try {
                JsonNode card = new ObjectMapper().readTree(raw);
                title = firstNonBlank(text(card, "title"), text(card, "prompt"));
            } catch (Exception ignored) {
                title = "";
            }
        } else {
            title = firstGroup(XML_TITLE, raw);
        }
        return title.isBlank() ? "[卡片]" : "[卡片] " + cap(title, 80);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static void putFirst(Map<String, String> values, String key, String... candidates) {
        if (values.containsKey(key)) {
            return;
        }
        String value = firstNonBlank(candidates);
        if (!value.isBlank()) {
            values.put(key, cap(value, 500));
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String firstGroup(Pattern pattern, String raw) {
        Matcher matcher = pattern.matcher(raw);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private static String cap(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
