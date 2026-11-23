package io.github.c1589190.bma3.qqofficial.shareqr;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqApiClient;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqApiException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime B share-QR workflow.
 *
 * <p>Order is intentional and fail-closed: validate the exact account id, resolve that account's
 * isolated API client, call {@code POST /v2/generate_url_link}, extract only
 * {@code data.url}, validate it against the official HTTPS share allow-list, render a PNG, then
 * atomically publish it below {@code <dataDir>/qr/}. No token, Authorization header, upstream
 * response body, or raw rejected URL is returned.</p>
 */
public final class OfficialQqShareQrService {

    /** Fixed non-sensitive callback marker documented by the upstream contract. */
    public static final String CALLBACK_DATA = "bma3";

    private final OfficialQqShareQrAccountResolver accountResolver;
    private final OfficialQqQrPngRenderer renderer;
    private final OfficialQqShareQrFileStore fileStore;

    public OfficialQqShareQrService(
            Path dataDir, OfficialQqShareQrAccountResolver accountResolver) {
        this(
                accountResolver,
                new OfficialQqQrPngRenderer(),
                new OfficialQqShareQrFileStore(dataDir));
    }

    OfficialQqShareQrService(
            OfficialQqShareQrAccountResolver accountResolver,
            OfficialQqQrPngRenderer renderer,
            OfficialQqShareQrFileStore fileStore) {
        this.accountResolver = Objects.requireNonNull(accountResolver, "accountResolver");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
    }

    /**
     * Generate one account's share QR. {@code accountId} is required and exact; there is no
     * single-account fallback.
     */
    public OfficialQqShareQrResult generate(String accountId) throws OfficialQqShareQrException {
        String safeAccountId = requireAccountId(accountId);
        Optional<OfficialQqApiClient> resolved;
        try {
            resolved = accountResolver.resolve(safeAccountId);
        } catch (RuntimeException failure) {
            throw new OfficialQqShareQrException(
                    OfficialQqShareQrException.ACCOUNT_RESOLUTION_FAILED,
                    "无法解析指定官方账号");
        }
        if (resolved == null) {
            throw new OfficialQqShareQrException(
                    OfficialQqShareQrException.ACCOUNT_RESOLUTION_FAILED,
                    "无法解析指定官方账号");
        }
        OfficialQqApiClient apiClient = resolved.orElseThrow(() -> new OfficialQqShareQrException(
                OfficialQqShareQrException.ACCOUNT_NOT_FOUND,
                "ACCOUNT_NOT_FOUND"));

        JsonNode response;
        try {
            response = apiClient.generateUrlLink(CALLBACK_DATA);
        } catch (OfficialQqApiException failure) {
            throw OfficialQqShareQrException.fromUpstream(failure);
        } catch (RuntimeException failure) {
            // Deliberately discard the exception: an unexpected client failure may contain
            // values that are not safe to expose. Classification is stable and non-sensitive.
            throw new OfficialQqShareQrException(
                    OfficialQqShareQrException.UPSTREAM_HTTP,
                    "QQ 官方分享接口调用失败");
        }

        String rawUrl = extractUrl(response);
        URI shareUrl = OfficialQqShareUrlValidator.validate(rawUrl);
        byte[] png = renderer.renderPng(shareUrl.toASCIIString());
        Path pngPath = fileStore.writePng(png);
        return new OfficialQqShareQrResult(shareUrl.toASCIIString(), pngPath, safeAccountId);
    }

    private static String extractUrl(JsonNode response) throws OfficialQqShareQrException {
        if (response == null || !response.isObject()) {
            throw malformedResponse();
        }
        JsonNode data = response.get("data");
        if (data == null || !data.isObject()) {
            throw malformedResponse();
        }
        JsonNode url = data.get("url");
        if (url == null || !url.isTextual()) {
            throw malformedResponse();
        }
        String value = url.textValue();
        if (value == null || value.isBlank()) {
            throw malformedResponse();
        }
        return value;
    }

    private static String requireAccountId(String accountId)
            throws OfficialQqShareQrException {
        if (accountId == null || accountId.isBlank() || accountId.length() > 128
                || !accountId.equals(accountId.strip())) {
            throw invalidAccountId();
        }
        for (int index = 0; index < accountId.length(); ) {
            int codePoint = accountId.codePointAt(index);
            int type = Character.getType(codePoint);
            if (codePoint == '/'
                    || codePoint == '\\'
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw invalidAccountId();
            }
            index += Character.charCount(codePoint);
        }
        return accountId;
    }

    private static OfficialQqShareQrException invalidAccountId() {
        return new OfficialQqShareQrException(
                OfficialQqShareQrException.INVALID_ARGUMENT,
                "accountId 必须是 1..128 字符的安全单段");
    }

    private static OfficialQqShareQrException malformedResponse() {
        return new OfficialQqShareQrException(
                OfficialQqShareQrException.MALFORMED_RESPONSE,
                "QQ 官方分享接口响应缺少合法 data.url");
    }
}
