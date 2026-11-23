package io.github.c1589190.bma3.app.official;

/**
 * Optional app-side hook that records what the channel router did with one official message.
 *
 * <p>This phase has no channel-binding implementation. The hook exists so the later app router can
 * persist/audit route decisions such as paused source, binding switch, raw fallback, canonical
 * merge, or evidence-only handling without changing ingest's board-first semantics.</p>
 *
 * <p>Observer failures are not swallowed: the Inbox sink has already been made idempotent by the
 * request key, so the gateway may replay safely and the app does not lose a route-decision
 * failure.</p>
 */
@FunctionalInterface
public interface OfficialQqRouteObserver {

    void onDecision(OfficialQqProjection projection, OfficialQqProjectionSink.Result result);

    static OfficialQqRouteObserver noop() {
        return (projection, result) -> {
        };
    }
}
