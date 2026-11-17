package io.mosire.bma3.qqofficial.send;

import io.mosire.bma3.qqofficial.account.OfficialQqAccountRegistry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Production-facing account gate backed by the official desired-state registry. */
public final class OfficialQqRegistrySendAccounts implements OfficialQqSendAccounts {

    private final OfficialQqAccountRegistry registry;
    private final Function<String, OfficialQqSendTransport> transports;

    public OfficialQqRegistrySendAccounts(
            OfficialQqAccountRegistry registry,
            Function<String, OfficialQqSendTransport> transports) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transports = Objects.requireNonNull(transports, "transports");
    }

    @Override
    public Optional<OfficialQqSendAccount> resolve(String accountId) {
        return registry.accountStatus(accountId)
                .map(status -> new OfficialQqSendAccount(
                        status.accountId(), status.enabled(),
                        status.enabled() ? transports.apply(status.accountId()) : null));
    }
}
