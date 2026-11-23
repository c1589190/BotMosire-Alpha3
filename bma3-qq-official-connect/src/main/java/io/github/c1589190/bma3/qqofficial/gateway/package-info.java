/**
 * QQ official Gateway state machine and injectable transport seam.
 *
 * <p>Public entry point: {@link io.github.c1589190.bma3.qqofficial.gateway.OfficialQqGatewayClient}.
 * The production transport is
 * {@link io.github.c1589190.bma3.qqofficial.gateway.JdkWebSocketGatewayTransport}; tests inject a fake
 * {@link io.github.c1589190.bma3.qqofficial.gateway.OfficialQqGatewayTransport}. Token/URI acquisition,
 * event dispatch, clock, scheduler and reconnect backoff are all caller-supplied interfaces.
 * No frame content or token is logged by this package. The production transport accepts only
 * secure official {@code wss://*.qq.com} URLs and bounds fragmented text frames; injected fake
 * transports bypass production URL checks. Dispatch sequence values distinguish received
 * (heartbeat) from sink-committed (Resume/replay) progress.</p>
 */
package io.github.c1589190.bma3.qqofficial.gateway;
