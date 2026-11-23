package io.github.c1589190.bma3.qqofficial.shareqr;

import java.nio.file.Path;
import java.util.Objects;

/** Public business result of a successful runtime share-QR generation. */
public record OfficialQqShareQrResult(String url, Path pngPath, String accountId) {

    public OfficialQqShareQrResult {
        url = Objects.requireNonNull(url, "url");
        pngPath = Objects.requireNonNull(pngPath, "pngPath").toAbsolutePath().normalize();
        accountId = Objects.requireNonNull(accountId, "accountId");
    }
}
