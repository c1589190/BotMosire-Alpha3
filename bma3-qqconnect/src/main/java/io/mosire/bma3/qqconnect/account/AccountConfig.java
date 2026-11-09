package io.mosire.bma3.qqconnect.account;

import java.net.URI;
import java.util.Objects;

/** Resolved account connection configuration; token is kept in memory only. */
public record AccountConfig(String accountId, URI endpoint, String token, boolean enabled) {

    public AccountConfig {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        Objects.requireNonNull(endpoint, "endpoint");
        token = token == null ? "" : token;
    }
}
