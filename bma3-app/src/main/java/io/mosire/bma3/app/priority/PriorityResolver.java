package io.mosire.bma3.app.priority;

import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.core.priority.PriorityAttributes;
import io.mosire.bma3.core.priority.PriorityRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Generic priority: one most-specific source rule plus additive attribute deltas.
 *
 * <p>The old model mixed three incompatible things into a single {@code max(...)} — a platform
 * weight, a per-source weight and a user-note "priority" — so a human note of 300 (meant as "this
 * person matters") silently equalled "always wake me". Here the numbers only ever mean score:
 * {@code sourcePriority} answers "what is this source worth", {@code attributes} answer "what did
 * this message do", and the score is compared against two explicit thresholds
 * ({@code dropBelow}, {@code immediateThreshold}) instead of being overloaded as a boolean.</p>
 *
 * <p>Pattern deltas live in the same attribute map: {@code qq_sender/<qq>} matches the message
 * sender and {@code qq_name/<substring>} matches the sender's display name, so "this person is
 * worth +50 in every group" is one config line rather than a second scoring system.</p>
 */
public final class PriorityResolver {

    public static final String SENDER_PREFIX = "qq_sender/";
    public static final String NAME_PREFIX = "qq_name/";

    private final Supplier<AppConfig.Weighting> weighting;

    public PriorityResolver(Supplier<AppConfig.Weighting> weighting) {
        this.weighting = weighting;
    }

    public PriorityDecision resolve(String sourceKey, String senderId, String senderName,
                                    PriorityAttributes attributes) {
        AppConfig.Weighting current = weighting.get();
        List<String> reasons = new ArrayList<>();

        int base = PriorityRules.match(current.sourcePriority(), sourceKey)
                .map(match -> {
                    reasons.add("source=" + sourceKey + " → " + match.ruleKey()
                            + " " + match.value());
                    return match.value();
                })
                .orElseGet(() -> {
                    reasons.add("source=" + sourceKey + " → 无规则，按 0");
                    return 0;
                });

        int delta = 0;
        if (attributes != null) {
            for (String key : attributes.keys()) {
                int value = current.attribute(key);
                if (value != 0) {
                    delta += value;
                    reasons.add(key + " " + signed(value));
                }
            }
        }
        delta += patternDelta(current, senderId, senderName, reasons);

        int raw = base + delta;
        int clipped = Math.min(current.maxPriority(), raw);
        if (clipped != raw) {
            reasons.add("clamp " + raw + " → " + clipped + "（maxPriority）");
        }
        boolean dropped = clipped < current.dropBelow();
        if (dropped) {
            reasons.add("total " + clipped + " < dropBelow " + current.dropBelow() + " ⇒ 丢弃");
        }
        boolean immediate = !dropped && clipped >= current.immediateThreshold();
        if (!dropped) {
            reasons.add("total " + clipped + (immediate ? " ≥ " : " < ")
                    + "immediateThreshold " + current.immediateThreshold()
                    + (immediate ? " ⇒ 立即推送" : " ⇒ 进入批次"));
        }
        return new PriorityDecision(clipped, dropped, immediate, reasons);
    }

    /** Adds {@code qq_sender/…} and {@code qq_name/…} rule values that match this message. */
    private static int patternDelta(AppConfig.Weighting current, String senderId,
                                    String senderName, List<String> reasons) {
        int delta = 0;
        String name = senderName == null ? "" : senderName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> entry : current.attributes().entrySet()) {
            String key = entry.getKey();
            Integer value = entry.getValue();
            if (key == null || value == null || value == 0) {
                continue;
            }
            if (key.startsWith(SENDER_PREFIX)) {
                String target = key.substring(SENDER_PREFIX.length());
                if (!target.isBlank() && target.equals(senderId)) {
                    delta += value;
                    reasons.add(key + " " + signed(value));
                }
                continue;
            }
            if (key.startsWith(NAME_PREFIX)) {
                String needle = key.substring(NAME_PREFIX.length());
                if (!needle.isBlank() && !name.isBlank()
                        && name.contains(needle.toLowerCase(Locale.ROOT))) {
                    delta += value;
                    reasons.add(key + " " + signed(value));
                }
            }
        }
        return delta;
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }
}
