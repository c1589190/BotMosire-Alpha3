package io.github.c1589190.bma3.inbox.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One bounded preview line in an inbox item. {@code text} is the plain text body;
 * {@code markers} contains short relation markers such as {@code [图片]} or
 * {@code [引用 #42 Alice]}. Image URLs and full forwarded content never go here.
 *
 * <p>Channel projection is generic and optional. {@code channel} is the channel that produced
 * this body line ({@code ""} for legacy rows), and {@code evidence} is opaque channel metadata
 * that callers may use to keep observation ids, corroboration states, or other diagnostic facts.
 * Inbox deliberately does not interpret channel names; it never switches on a platform.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboxEntry(
        long localSeq,
        Instant at,
        String sender,
        String text,
        List<String> markers,
        String channel,
        Map<String, Object> evidence) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public InboxEntry {
        at = at == null ? Instant.now() : at;
        sender = sender == null ? "" : sender;
        text = text == null ? "" : text;
        markers = markers == null ? List.of() : List.copyOf(markers);
        channel = channel == null ? "" : channel.trim();
        evidence = immutableMap(evidence);
    }

    /** Backward-compatible constructor for legacy callers and old persisted JSON (no channel). */
    public InboxEntry(long localSeq, Instant at, String sender, String text, List<String> markers) {
        this(localSeq, at, sender, text, markers, "", Map.of());
    }

    /** Convenience constructor for one projected body line without evidence details. */
    public InboxEntry(long localSeq, Instant at, String sender, String text, List<String> markers,
                      String channel) {
        this(localSeq, at, sender, text, markers, channel, Map.of());
    }

    /** Copy with one extra display marker (used for the "续" continuation mark). */
    public InboxEntry withMarker(String marker) {
        if (marker == null || marker.isBlank() || markers.contains(marker)) {
            return this;
        }
        List<String> next = new java.util.ArrayList<>(markers);
        next.add(marker);
        return new InboxEntry(localSeq, at, sender, text, next, channel, evidence);
    }

    /** Copy with channel/evidence metadata; the rendered body is untouched. */
    public InboxEntry withChannelEvidence(String nextChannel, Map<String, Object> nextEvidence) {
        return new InboxEntry(localSeq, at, sender, text, markers, nextChannel, nextEvidence);
    }

    public String render() {
        return render(ZoneId.systemDefault());
    }

    /** Server-rendered line for the panel JSON; clients need not duplicate the format. */
    @JsonProperty("line")
    public String line() {
        return render();
    }

    public String render(ZoneId zone) {
        var when = at.atZone(zone == null ? ZoneId.systemDefault() : zone);
        String stamp = when.toLocalDate().equals(LocalDate.now(when.getZone()))
                ? when.format(TIME) : when.format(DATE_TIME);
        String who = sender.isBlank() ? "" : sender;
        String mark = markers.isEmpty() ? "" : " " + String.join(" ", markers);
        return "[" + stamp + "] #" + localSeq + " " + who + "：" + text + mark;
    }

    static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
