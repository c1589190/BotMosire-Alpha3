package io.mosire.bma3.app.official;

import io.mosire.bma3.inbox.core.AppendItem;
import io.mosire.bma3.inbox.core.InboxItem;
import io.mosire.bma3.inbox.core.InboxService;
import java.util.Objects;

/**
 * Default single-channel Inbox sink for official raw sources.
 *
 * <p>This is intentionally a fallback, not the final dual-channel router: it projects the raw
 * {@code qq_official_*} source with display channel {@code qq_official}. The future app channel
 * router can replace it and use the same projection contract to merge a canonical
 * {@code qq_thread/*} source or write evidence-only rows.</p>
 *
 * <p>Every request uses {@link InboxService#appendProjectedOnce(String, AppendItem)}. That method
 * commits the durable receipt and item mutation in one SQLite transaction. Consequently a gateway
 * replay after a process crash between board append and Inbox projection can recover, while a
 * duplicate after a successful projection is a read-only no-op and schedules no second wake.</p>
 */
public final class OfficialInboxProjectionSink implements OfficialQqProjectionSink {

    public static final String CHANNEL = "qq_official";
    public static final String ROUTE = "raw";

    private final InboxService inbox;

    public OfficialInboxProjectionSink(InboxService inbox) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
    }

    @Override
    public Result project(OfficialQqProjection projection) {
        Objects.requireNonNull(projection, "projection");
        String channel = projection.displayChannel().isBlank() ? CHANNEL
                : projection.displayChannel();
        AppendItem item = AppendItem.projected(
                projection.source(),
                projection.entry(),
                projection.priority().priority(),
                projection.availableAt(),
                channel,
                projection.channelSummary());
        InboxItem appended = inbox.appendProjectedOnce(projection.requestKey(), item);
        return Result.projected(ROUTE, "append_projected_once",
                projection.source().raw(), channel)
                .withProjected(appended != null);
    }
}
