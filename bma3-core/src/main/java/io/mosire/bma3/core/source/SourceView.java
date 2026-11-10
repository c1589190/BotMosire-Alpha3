package io.mosire.bma3.core.source;

import java.util.List;

/**
 * Adapter-provided view of one source: the opaque address plus its display label.
 * The Inbox stores this value; it never branches on the platform.
 */
public record SourceView(
        String raw,
        String platform,
        String accountId,
        List<String> route,
        String label) {

    public SourceView {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("source 不能为空");
        }
        platform = platform == null || platform.isBlank() ? SourceAddress.parse(raw).platform() : platform;
        accountId = accountId == null ? "" : accountId;
        route = route == null ? List.of() : List.copyOf(route);
        label = label == null || label.isBlank() ? SourceAddress.parse(raw).displayFallback() : label;
    }

    public static SourceView of(SourceAddress source, String label) {
        return new SourceView(source.format(), source.platform(), source.accountId(),
                source.route(), label);
    }

    public static SourceView of(String raw, String label) {
        return of(SourceAddress.parse(raw), label);
    }
}
