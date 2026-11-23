package io.github.c1589190.bma3.qqofficial.shareqr;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Strict allow-list validation for the short link returned by
 * {@code POST /v2/generate_url_link}.
 *
 * <p>Only the official HTTPS share origin and its short-link path are accepted. The validator
 * rejects userinfo, any other host, explicit non-HTTPS schemes, non-default ports, query strings,
 * fragments, encoded path tricks and control characters. A rejected value is never echoed back in
 * the sanitized exception.</p>
 */
public final class OfficialQqShareUrlValidator {

    public static final String OFFICIAL_HOST = "bot.q.qq.com";
    public static final String SHARE_PATH_PREFIX = "/s/";
    public static final int MAX_URL_LENGTH = 2048;

    private static final Pattern SHORT_CODE =
            Pattern.compile("/s/[A-Za-z0-9._~-]{1,256}");

    private OfficialQqShareUrlValidator() {
    }

    /**
     * Return a canonical official share URI or fail with {@link OfficialQqShareQrException}.
     *
     * <p>The returned value is safe to expose as the tool's business result: it is exactly the
     * validated official origin plus the short-link path. The raw upstream string is never copied
     * to an exception.</p>
     */
    public static URI validate(String rawUrl) throws OfficialQqShareQrException {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > MAX_URL_LENGTH) {
            throw invalid();
        }
        if (!rawUrl.equals(rawUrl.strip()) || containsControlOrWhitespace(rawUrl)) {
            throw invalid();
        }

        URI parsed;
        try {
            parsed = URI.create(rawUrl);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }

        if (!parsed.isAbsolute()
                || parsed.isOpaque()
                || !"https".equalsIgnoreCase(parsed.getScheme())
                || parsed.getRawUserInfo() != null
                || parsed.getHost() == null
                || !OFFICIAL_HOST.equals(parsed.getHost().toLowerCase(Locale.ROOT))
                || (parsed.getPort() != -1 && parsed.getPort() != 443)
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null) {
            throw invalid();
        }

        String rawPath = parsed.getRawPath();
        if (rawPath == null
                || "/s/.".equals(rawPath)
                || "/s/..".equals(rawPath)
                || !SHORT_CODE.matcher(rawPath).matches()) {
            throw invalid();
        }
        // The raw path is already restricted to an unreserved ASCII short code. Rebuilding from
        // constants makes it impossible for an unusual authority or opaque form to survive.
        try {
            return URI.create("https://" + OFFICIAL_HOST + rawPath);
        } catch (IllegalArgumentException impossible) {
            throw invalid();
        }
    }

    private static boolean containsControlOrWhitespace(String value) {
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static OfficialQqShareQrException invalid() {
        return new OfficialQqShareQrException(
                OfficialQqShareQrException.INVALID_SHARE_URL,
                "分享接口返回的 URL 不是受支持的官方 https 分享链接");
    }
}
