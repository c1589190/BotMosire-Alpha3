package io.github.c1589190.bma3.app.official;

/**
 * App-side routing/projection extension point for official messages.
 *
 * <p>Production wiring is expected to inject the future app channel router here. The router gets
 * the raw decoded event, the message-board append result (including duplicate/replay status), the
 * priority decision, the already-sanitized Inbox entry, and a stable request key. It can therefore
 * project the raw single channel now, later merge into a canonical logical thread, write
 * evidence-only data for a non-display channel, or explicitly skip injection while still leaving
 * the board fact intact.</p>
 *
 * <p><b>Idempotency contract:</b> a successful implementation must make
 * {@link OfficialQqProjection#requestKey()} idempotent and durable. {@link OfficialInboxProjectionSink}
 * does this with {@code InboxService.appendProjectedOnce}. A runtime/checked failure must be
 * thrown, never swallowed: the gateway can then resume and replay the event.</p>
 */
@FunctionalInterface
public interface OfficialQqProjectionSink {

    /**
     * @throws Exception when projection failed; ingest rethrows/wraps it for gateway replay
     */
    Result project(OfficialQqProjection projection) throws Exception;

    /**
     * Stable, non-sensitive outcome used by the route-decision observer.
     *
     * @param projected true when this call caused/applied an Inbox projection (a durable receipt
     *                  may mean a replay changed nothing)
     * @param route app-owned route name such as {@code raw}, {@code canonical}, or
     *              {@code evidence_only}
     * @param reason app-owned safe diagnostic code; must not contain message bodies or credentials
     * @param canonicalSource selected Inbox source, or empty when no projection was made
     * @param displayChannel selected display channel, or empty when no body projection was made
     */
    record Result(boolean projected, String route, String reason,
                  String canonicalSource, String displayChannel) {

        public Result {
            route = safeCode(route, "unknown");
            reason = safeCode(reason, "");
            canonicalSource = canonicalSource == null ? "" : canonicalSource.trim();
            displayChannel = displayChannel == null ? "" : displayChannel.trim();
        }

        public static Result projected(String route, String reason,
                                       String canonicalSource, String displayChannel) {
            return new Result(true, route, reason, canonicalSource, displayChannel);
        }

        public static Result evidenceOnly(String route, String reason, String canonicalSource,
                                          String displayChannel) {
            return new Result(false, route, reason, canonicalSource, displayChannel);
        }

        public static Result skipped(String route, String reason) {
            return new Result(false, route, reason, "", "");
        }

        /** Keep the route decision but reflect whether a durable item was returned. */
        public Result withProjected(boolean value) {
            return new Result(value, route, reason, canonicalSource, displayChannel);
        }

        private static String safeCode(String value, String fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            String trimmed = value.strip();
            if (trimmed.length() > 128 || trimmed.codePoints().anyMatch(codePoint ->
                    Character.isISOControl(codePoint)
                            || Character.isWhitespace(codePoint)
                            || Character.isSpaceChar(codePoint)
                            || Character.getType(codePoint) == Character.FORMAT)) {
                return fallback;
            }
            return trimmed;
        }
    }
}
