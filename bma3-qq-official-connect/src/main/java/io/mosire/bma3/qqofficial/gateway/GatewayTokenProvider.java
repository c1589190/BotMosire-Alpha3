package io.mosire.bma3.qqofficial.gateway;

/**
 * Supplies the raw QQ official access token.
 *
 * <p>The Gateway client adds the protocol-required {@code QQBot } prefix and never logs or stores
 * the returned value in status. Production wiring should adapt the Phase-2 token manager here.</p>
 */
@FunctionalInterface
public interface GatewayTokenProvider {

    String accessToken() throws Exception;
}
