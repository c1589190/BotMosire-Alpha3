package io.github.c1589190.bma3.qqofficial.media;

import java.util.Objects;

/** One enabled/disabled media-account decision plus its exact transport binding. */
public record OfficialQqMediaAccount(String accountId, boolean enabled,
                                     OfficialQqMediaTransportPort transport) {

    public OfficialQqMediaAccount {
        accountId = accountId == null ? "" : accountId.strip();
        if (accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        if (enabled) {
            Objects.requireNonNull(transport, "transport");
            if (!accountId.equals(transport.accountId())) {
                throw new IllegalArgumentException("账号与 transport 绑定不一致");
            }
        } else if (transport != null && !accountId.equals(transport.accountId())) {
            throw new IllegalArgumentException("账号与 transport 绑定不一致");
        }
    }
}
