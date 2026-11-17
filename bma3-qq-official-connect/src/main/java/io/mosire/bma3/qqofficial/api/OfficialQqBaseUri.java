package io.mosire.bma3.qqofficial.api;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Base-URI policy for the official QQ client.
 *
 * <p>Production construction always uses the fixed official HTTPS origin. A caller-selected HTTP
 * or alternate-host base URI is accepted only through an explicitly named loopback-test factory so
 * an AppSecret-bearing token request cannot accidentally be routed to an arbitrary host.</p>
 */
public final class OfficialQqBaseUri {

    public static final URI PRODUCTION = URI.create("https://api.bot.qq.com");
    private static final String PRODUCTION_TEXT = PRODUCTION.toString();

    private OfficialQqBaseUri() {
    }

    /** Require the one fixed production origin. */
    public static URI requireProduction(URI baseUri) {
        Objects.requireNonNull(baseUri, "baseUri");
        URI normalized = withoutTrailingSlash(baseUri);
        if (!PRODUCTION_TEXT.equals(normalized.toString())) {
            throw new IllegalArgumentException(
                    "生产 baseUri 必须固定为 https://api.bot.qq.com");
        }
        return normalized;
    }

    /**
     * Allow an alternate base URI only when it is an explicit loopback address. This method does not
     * resolve hostnames; only the literal loopback names below are accepted.
     */
    public static URI requireLoopbackTesting(URI baseUri) {
        Objects.requireNonNull(baseUri, "baseUri");
        URI normalized = withoutTrailingSlash(baseUri);
        String scheme = normalized.getScheme() == null
                ? "" : normalized.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("测试 baseUri 必须是 http/https");
        }
        if (normalized.getUserInfo() != null
                || normalized.getQuery() != null
                || normalized.getFragment() != null) {
            throw new IllegalArgumentException("测试 baseUri 不能含 userinfo/query/fragment");
        }
        String path = normalized.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new IllegalArgumentException("测试 baseUri 不能含路径前缀");
        }
        String host = normalized.getHost();
        if (host == null || !isLoopbackLiteral(host)) {
            throw new IllegalArgumentException("测试 baseUri 只能指向回环地址");
        }
        return normalized;
    }

    private static boolean isLoopbackLiteral(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.equals("localhost")
                || isIpv4Loopback(value)
                || value.equals("::1")
                || value.equals("0:0:0:0:0:0:0:1");
    }

    private static boolean isIpv4Loopback(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4 || !"127".equals(parts[0])) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.matches("[0-9]+")) {
                return false;
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static URI withoutTrailingSlash(URI uri) {
        String text = uri.toString();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return URI.create(text);
    }
}
