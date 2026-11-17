package io.mosire.bma3.qqofficial.api;

/** Optional metrics/audit sink. Implementations must not add request or response bodies. */
@FunctionalInterface
public interface OfficialQqHttpObserver {
    void onHttp(OfficialQqHttpEvent event);
}
