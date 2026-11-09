package io.mosire.bma3.core.account;

import java.util.Objects;

/** Stable business identity of one connected account. */
public record AccountId(String value) {

    public AccountId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
    }
}
