package io.github.c1589190.bma3.qqofficial.gateway;

import java.net.URI;

/**
 * Supplies a fresh WebSocket URL from {@code GET /gateway/bot}.
 *
 * <p>It is called for every connection attempt so a provider can follow platform URL rotation.</p>
 */
@FunctionalInterface
public interface GatewayUriProvider {

    URI gatewayUri() throws Exception;
}
