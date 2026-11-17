package io.mosire.bma3.qqofficial.account;

/**
 * Creates one account-isolated official runtime.
 *
 * <p>This is the primary fake seam for registry tests and the production composition seam for the
 * application root. A factory must not share a token manager, gateway client, API client or event
 * sink across accounts; each call receives one account config and one account-scoped dispatch
 * sink.</p>
 */
@FunctionalInterface
public interface OfficialQqAccountClientFactory {

    OfficialQqAccountClient create(OfficialQqAccountClientContext context) throws Exception;
}
