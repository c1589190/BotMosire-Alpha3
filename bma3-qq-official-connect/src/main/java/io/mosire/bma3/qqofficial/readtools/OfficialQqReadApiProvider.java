package io.mosire.bma3.qqofficial.readtools;

import io.mosire.bma3.qqofficial.api.OfficialQqApiException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Injects an account-isolated read API; the app owns token/runtime wiring. */
@FunctionalInterface
public interface OfficialQqReadApiProvider {

    OfficialQqReadApi forAccount(String accountId) throws OfficialQqApiException;

    static OfficialQqReadApiProvider unavailable() {
        return accountId -> {
            throw new OfficialQqApiException(
                    io.mosire.bma3.qqofficial.api.OfficialQqErrorKind.CONFIGURATION,
                    -1,
                    accountId,
                    "READ_API",
                    "",
                    "",
                    false,
                    "read API unavailable",
                    null);
        };
    }

    static OfficialQqReadApiProvider fromClients(Map<String, OfficialQqReadApi> clients) {
        Objects.requireNonNull(clients, "clients");
        Map<String, OfficialQqReadApi> copy = Map.copyOf(clients);
        return accountId -> Optional.ofNullable(copy.get(accountId))
                .orElseThrow(() -> new OfficialQqApiException(
                        io.mosire.bma3.qqofficial.api.OfficialQqErrorKind.CONFIGURATION,
                        -1,
                        accountId,
                        "READ_API",
                        "",
                        "",
                        false,
                        "read API unavailable",
                        null));
    }
}
