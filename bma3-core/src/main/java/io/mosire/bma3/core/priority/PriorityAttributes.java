package io.mosire.bma3.core.priority;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter-neutral, <b>opaque</b> priority attributes observed on one message.
 *
 * <p>Keys are plain strings supplied by the adapter (for example {@code qq_at_bot},
 * {@code qq_at_all}, {@code qq_reply_to_bot}, {@code qq_self_name}, {@code qq_command}). The core
 * and the Inbox never branch on them: the resolver only looks the key up in
 * {@code weighting.attributes} and adds the configured delta. A new platform therefore needs no
 * core change, only its own extractor.</p>
 */
public record PriorityAttributes(Set<String> keys) {

    public PriorityAttributes {
        if (keys == null || keys.isEmpty()) {
            keys = Set.of();
        } else {
            Set<String> copy = new LinkedHashSet<>();
            keys.forEach(key -> {
                if (key != null && !key.isBlank()) {
                    copy.add(key);
                }
            });
            keys = Set.copyOf(copy);
        }
    }

    public static PriorityAttributes none() {
        return new PriorityAttributes(Set.of());
    }

    public static PriorityAttributes of(String... keys) {
        return new PriorityAttributes(keys == null ? Set.of() : new LinkedHashSet<>(List.of(keys)));
    }

    public boolean has(String key) {
        return keys.contains(key);
    }

    public int size() {
        return keys.size();
    }
}
