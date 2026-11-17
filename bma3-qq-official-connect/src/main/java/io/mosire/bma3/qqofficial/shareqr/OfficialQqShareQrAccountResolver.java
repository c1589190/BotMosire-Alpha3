package io.mosire.bma3.qqofficial.shareqr;

import io.mosire.bma3.qqofficial.api.OfficialQqApiClient;
import java.util.Optional;

/**
 * Resolves the already-account-isolated official API client for a concrete account id.
 *
 * <p>The production composition root owns the account registry, token managers and API clients.
 * Returning {@link Optional#empty()} means the account is not configured and must fail closed
 * before any secret, token, directory or network operation is attempted. Implementations must use
 * an exact id match and must never fall back to "the only configured account" for a missing or
 * untrusted account id.</p>
 */
@FunctionalInterface
public interface OfficialQqShareQrAccountResolver {

    Optional<OfficialQqApiClient> resolve(String accountId);
}
