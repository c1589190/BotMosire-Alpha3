package io.mosire.bma3.qqofficial.account;

import java.time.Duration;
import java.util.Objects;

/**
 * Credential-free runtime tuning for one official account.
 *
 * <p>These values mirror {@code AppConfig.QqOfficial.Http/Reconnect}. They are part of
 * {@link OfficialQqAccountConfig#equals(Object)}, so changing a timeout or backoff parameter on a
 * later reconcile safely retires the old runtime before starting the replacement. No credential,
 * token or endpoint value belongs in this record.</p>
 */
public record OfficialQqAccountRuntimeOptions(
        Duration httpConnectTimeout,
        Duration httpRequestTimeout,
        Duration reconnectInitial,
        Duration reconnectMax,
        boolean reconnectJitter) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    public static final Duration DEFAULT_RECONNECT_INITIAL = Duration.ofSeconds(1);
    public static final Duration DEFAULT_RECONNECT_MAX = Duration.ofSeconds(60);

    public OfficialQqAccountRuntimeOptions {
        httpConnectTimeout = requireRange(
                httpConnectTimeout, "httpConnectTimeout", Duration.ofSeconds(1),
                Duration.ofSeconds(300));
        httpRequestTimeout = requireRange(
                httpRequestTimeout, "httpRequestTimeout", Duration.ofSeconds(1),
                Duration.ofSeconds(600));
        reconnectInitial = requireRange(
                reconnectInitial, "reconnectInitial", Duration.ofMillis(100),
                Duration.ofSeconds(60));
        reconnectMax = requireRange(
                reconnectMax, "reconnectMax", reconnectInitial, Duration.ofSeconds(600));
    }

    public static OfficialQqAccountRuntimeOptions defaults() {
        return new OfficialQqAccountRuntimeOptions(
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_RECONNECT_INITIAL,
                DEFAULT_RECONNECT_MAX,
                true);
    }

    /** Convenience for app config, whose timeout fields are integer seconds. */
    public static OfficialQqAccountRuntimeOptions ofSeconds(
            int connectTimeoutSeconds,
            int requestTimeoutSeconds,
            long reconnectInitialMs,
            long reconnectMaxMs,
            boolean reconnectJitter) {
        return new OfficialQqAccountRuntimeOptions(
                Duration.ofSeconds(connectTimeoutSeconds),
                Duration.ofSeconds(requestTimeoutSeconds),
                Duration.ofMillis(reconnectInitialMs),
                Duration.ofMillis(reconnectMaxMs),
                reconnectJitter);
    }

    private static Duration requireRange(
            Duration value, String name, Duration minimum, Duration maximum) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()
                || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " 超出允许范围");
        }
        return value;
    }

    @Override
    public String toString() {
        return "OfficialQqAccountRuntimeOptions[httpConnectTimeout=" + httpConnectTimeout
                + ", httpRequestTimeout=" + httpRequestTimeout
                + ", reconnectInitial=" + reconnectInitial
                + ", reconnectMax=" + reconnectMax
                + ", reconnectJitter=" + reconnectJitter + "]";
    }
}
