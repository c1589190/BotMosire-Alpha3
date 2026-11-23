package io.github.c1589190.bma3.qqofficial.account;

import io.github.c1589190.bma3.qqofficial.api.OfficialQqBaseUri;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Endpoint policy for the official QQ channel.
 *
 * <p>The production default is the fixed official API base URL. There is intentionally no
 * per-account endpoint override in {@link OfficialQqAccountConfig}: account configuration selects
 * credentials and shard, never a host. Tests and explicitly configured non-production factories
 * may pass a different base URI through the registry constructor, but production wiring should use
 * {@link #OFFICIAL_API_BASE_URI} and {@link #requireOfficialGatewayUri(URI)}.</p>
 */
public final class OfficialQqAccountEndpoints {

    /** The only production OpenAPI base used by this module. */
    public static final URI OFFICIAL_API_BASE_URI = OfficialQqBaseUri.PRODUCTION;

    private OfficialQqAccountEndpoints() {
    }

    /** Validates and normalizes an API base URI, removing a trailing slash. */
    public static URI requireApiBaseUri(URI uri) {
        Objects.requireNonNull(uri, "apiBaseUri");
        String scheme = lower(uri.getScheme());
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("apiBaseUri 必须是 http/https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("apiBaseUri 缺少 host");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("apiBaseUri 不能含 userinfo/query/fragment");
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new IllegalArgumentException("apiBaseUri 不能含路径前缀");
        }
        return URI.create(uri.toString().replaceFirst("/+$", ""));
    }

    /**
     * True only for the fixed official OpenAPI origin. This is a policy helper, not a substitute
     * for TLS verification by {@link java.net.http.HttpClient}.
     */
    public static boolean isOfficialApiBaseUri(URI uri) {
        if (uri == null || !"https".equals(lower(uri.getScheme()))) {
            return false;
        }
        return "api.bot.qq.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()));
    }

    /**
     * Validates a gateway URI returned by the official API. The platform currently returns hosts
     * under {@code qq.com}; a gateway pointing anywhere else must fail closed before a token is
     * sent to it.
     */
    public static URI requireOfficialGatewayUri(URI uri) {
        Objects.requireNonNull(uri, "gatewayUri");
        String scheme = lower(uri.getScheme());
        if (!"wss".equals(scheme)) {
            throw new IllegalArgumentException("gatewayUri 必须使用 wss");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("gatewayUri 缺少 host");
        }
        String normalized = lower(host);
        if (!normalized.equals("qq.com") && !normalized.endsWith(".qq.com")) {
            throw new IllegalArgumentException("gatewayUri 不是官方 QQ 域名");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("gatewayUri 不能含 userinfo/fragment");
        }
        return uri;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
