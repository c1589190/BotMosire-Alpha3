package io.github.c1589190.bma3.qqofficial.gateway;

/** Receives protocol Dispatch frames after READY/RESUMED bookkeeping. */
@FunctionalInterface
public interface GatewayEventSink {

    void onDispatch(GatewayDispatch dispatch);

    static GatewayEventSink noop() {
        return dispatch -> {
        };
    }
}
