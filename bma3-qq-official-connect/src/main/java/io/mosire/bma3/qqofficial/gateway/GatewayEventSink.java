package io.mosire.bma3.qqofficial.gateway;

/** Receives protocol Dispatch frames after READY/RESUMED bookkeeping. */
@FunctionalInterface
public interface GatewayEventSink {

    void onDispatch(GatewayDispatch dispatch);

    static GatewayEventSink noop() {
        return dispatch -> {
        };
    }
}
