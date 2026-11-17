package io.mosire.bma3.qqofficial.account;

import io.mosire.bma3.qqofficial.credential.OfficialQqCredential;
import io.mosire.bma3.qqofficial.event.OfficialQqDispatch;
import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Inputs handed to an {@link OfficialQqAccountClientFactory}.
 *
 * <p>{@code dispatchSink} is already scoped to this account by the registry: the factory should
 * feed decoded dispatches to it and must not add an account id of its own. The production factory
 * should use {@code apiBaseUri} (default: official endpoint) for token/API/gateway discovery and
 * must pass the returned gateway URI through
 * {@link OfficialQqAccountEndpoints#requireOfficialGatewayUri(URI)}.</p>
 *
 * <p>Credential ownership transfers to the created runtime, but the registry also calls
 * {@link OfficialQqCredential#close()} when it retires the runtime. Implementations must therefore
 * tolerate a defensive second close.</p>
 */
public record OfficialQqAccountClientContext(
        OfficialQqAccountConfig config,
        OfficialQqCredential credential,
        URI apiBaseUri,
        Consumer<OfficialQqDispatch> dispatchSink,
        Consumer<OfficialQqAccountRuntimeStatus> statusSink) {

    public OfficialQqAccountClientContext {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(credential, "credential");
        apiBaseUri = OfficialQqAccountEndpoints.requireApiBaseUri(apiBaseUri);
        Objects.requireNonNull(dispatchSink, "dispatchSink");
        statusSink = statusSink == null ? status -> { } : statusSink;
        if (!config.appId().equals(credential.appId())) {
            throw new IllegalArgumentException("credential/appId 与 account config 不一致");
        }
    }

    @Override
    public String toString() {
        return "OfficialQqAccountClientContext[accountId=" + config.accountId()
                + ", appId=" + config.appId()
                + ", apiBaseUri=" + apiBaseUri
                + ", credential=<redacted>]";
    }
}
