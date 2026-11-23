package io.github.c1589190.bma3.app.channel.router;

import java.util.Objects;

/** Safe, body-free outcome of routing one QQ message. */
public record QqChannelRouteResult(
        Disposition disposition,
        String route,
        String reason,
        String canonicalSource,
        String displayChannel,
        String itemId,
        String observationId) {

    public QqChannelRouteResult {
        Objects.requireNonNull(disposition, "disposition");
        route = route == null ? "" : route;
        reason = reason == null ? "" : reason;
        canonicalSource = canonicalSource == null ? "" : canonicalSource;
        displayChannel = displayChannel == null ? "" : displayChannel;
        itemId = itemId == null ? "" : itemId;
        observationId = observationId == null ? "" : observationId;
    }

    public enum Disposition {
        DROPPED,
        UNBOUND_RAW,
        BOUND_DISPLAY,
        BOUND_EVIDENCE,
        BOUND_GATE_OFF,
        DUPLICATE
    }

    public static QqChannelRouteResult dropped() {
        return new QqChannelRouteResult(Disposition.DROPPED, "drop", "below_drop_threshold",
                "", "", "", "");
    }
}
