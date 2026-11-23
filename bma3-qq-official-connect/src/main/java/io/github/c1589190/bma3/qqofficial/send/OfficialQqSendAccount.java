package io.github.c1589190.bma3.qqofficial.send;

import java.util.Objects;

/** One enabled/disabled account decision plus its provider transport. */
public record OfficialQqSendAccount(String accountId, boolean enabled,
                                    OfficialQqSendTransport transport) {

    public OfficialQqSendAccount {
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
