package io.mosire.bma3.qqofficial.credential;

import java.util.Arrays;

/**
 * In-memory AppID/AppSecret pair. The secret is held in a mutable char array and is never exposed
 * by {@link #toString()} or any accessor returning a String. Callers that must build a wire body
 * obtain a clone and zero it after use.
 */
public final class OfficialQqCredential implements AutoCloseable {

    private final String appId;
    private final char[] appSecret;
    private boolean closed;

    OfficialQqCredential(String appId, char[] appSecret) {
        this.appId = appId;
        this.appSecret = appSecret.clone();
    }

    public String appId() {
        return appId;
    }

    /** Return a defensive copy so the caller can zero the secret immediately after use. */
    public synchronized char[] copySecret() {
        if (closed) {
            throw new IllegalStateException("credential 已关闭");
        }
        return appSecret.clone();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(appSecret, '\0');
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "OfficialQqCredential[appId=" + appId + ", appSecret=<redacted>]";
    }
}
