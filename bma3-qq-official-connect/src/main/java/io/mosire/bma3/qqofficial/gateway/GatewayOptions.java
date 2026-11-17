package io.mosire.bma3.qqofficial.gateway;

import java.time.Duration;
import java.util.Objects;

/** Credential-free protocol options for one official QQ account. */
public record GatewayOptions(
        String accountId,
        int intents,
        int shardIndex,
        int shardCount,
        Duration heartbeatAckTimeout,
        String os,
        String browser,
        String device) {

    public static final int GROUP_AND_C2C_EVENT = 1 << 25;

    public GatewayOptions {
        accountId = Objects.requireNonNull(accountId, "accountId").trim();
        if (accountId.isEmpty()) {
            throw new IllegalArgumentException("accountId must not be blank");
        }
        if (shardIndex < 0 || shardCount < 1 || shardIndex >= shardCount) {
            throw new IllegalArgumentException("invalid shard index/count");
        }
        if (heartbeatAckTimeout != null
                && (heartbeatAckTimeout.isZero() || heartbeatAckTimeout.isNegative())) {
            throw new IllegalArgumentException("heartbeatAckTimeout must be positive");
        }
        os = textOr(os, "linux");
        browser = textOr(browser, "bma3");
        device = textOr(device, "bma3");
    }

    public static GatewayOptions defaults(String accountId) {
        return new GatewayOptions(accountId, GROUP_AND_C2C_EVENT, 0, 1, null,
                "linux", "bma3", "bma3");
    }

    public GatewayOptions withHeartbeatAckTimeout(Duration timeout) {
        return new GatewayOptions(accountId, intents, shardIndex, shardCount, timeout,
                os, browser, device);
    }

    public GatewayOptions withShard(int index, int count) {
        return new GatewayOptions(accountId, intents, index, count, heartbeatAckTimeout,
                os, browser, device);
    }

    public GatewayOptions withIntents(int value) {
        return new GatewayOptions(accountId, value, shardIndex, shardCount, heartbeatAckTimeout,
                os, browser, device);
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
