package io.mosire.bma3.qqofficial.account;

import io.mosire.bma3.qqofficial.api.OfficialQqApiClient;
import java.util.Optional;

/**
 * One live official-account runtime owned by the registry.
 *
 * <p>Implementations normally wrap a token manager, OpenAPI client and
 * {@link io.mosire.bma3.qqofficial.gateway.OfficialQqGatewayClient}. The registry calls
 * {@link #start()} once, polls {@link #status()} for safe diagnostics, and calls {@link #close()}
 * before replacing or removing the account. {@code close()} must be idempotent and must make all
 * later callbacks inert and all reconnect attempts impossible.</p>
 */
public interface OfficialQqAccountClient extends AutoCloseable {

    String accountId();

    void start() throws Exception;

    OfficialQqAccountRuntimeStatus status();

    /**
     * Account-scoped OpenAPI client for read/send tools, when this runtime supports one.
     *
     * <p>Present means the runtime is still active; it does not imply the Gateway is READY.
     * Callers must handle credential-free typed {@link
     * io.mosire.bma3.qqofficial.api.OfficialQqApiException} failures and must not cache this
     * client beyond the holder generation. After {@link #close()} or registry retirement this
     * method must return empty or the underlying token manager must fail closed. The returned
     * client never exposes the AccessToken, Authorization header or AppSecret.</p>
     */
    default Optional<OfficialQqApiClient> apiClient() {
        return Optional.empty();
    }

    @Override
    void close();
}
