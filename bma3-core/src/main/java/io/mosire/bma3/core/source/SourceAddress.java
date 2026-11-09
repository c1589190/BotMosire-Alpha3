package io.mosire.bma3.core.source;

import java.util.Arrays;
import java.util.List;

/**
 * Canonical, opaque source address such as {@code qq_group/bot-1/955432692}.
 *
 * <p>The first token is the platform/route (for example {@code qq_group}), the second is the
 * account id, and the remaining tokens are route-specific. The value is preserved verbatim so
 * that layers can hand it back to Agent tools without re-encoding.
 */
public record SourceAddress(String value) {

    public SourceAddress {
        validate(value);
    }

    public static SourceAddress parse(String raw) {
        validate(raw);
        return new SourceAddress(raw);
    }

    public String format() {
        return value;
    }

    public String platform() {
        return tokens()[0];
    }

    public String accountId() {
        String[] tokens = tokens();
        return tokens.length > 2 ? tokens[1] : "";
    }

    /** Route tokens after the platform (the first one is normally the account id). */
    public List<String> segments() {
        String[] tokens = tokens();
        return List.copyOf(Arrays.asList(tokens).subList(1, tokens.length));
    }

    private String[] tokens() {
        return value.split("/", -1);
    }

    private static void validate(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
        if (raw.isBlank()) {
            throw new IllegalArgumentException("source 不能为空");
        }
        String[] tokens = raw.split("/", -1);
        if (tokens.length < 2) {
            throw new IllegalArgumentException("source 至少需要 platform/route: " + raw);
        }
        for (String token : tokens) {
            if (token.isBlank()) {
                throw new IllegalArgumentException("source 不能包含空段: " + raw);
            }
        }
    }
}
