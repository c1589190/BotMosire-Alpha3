package io.github.c1589190.bma3.qqofficial.account;

/** Receives account-scoped decoded official events for message-board/Inbox ingest. */
@FunctionalInterface
public interface OfficialQqAccountEventSink {

    void onEvent(OfficialQqAccountEvent event);

    static OfficialQqAccountEventSink noop() {
        return event -> {
        };
    }
}
