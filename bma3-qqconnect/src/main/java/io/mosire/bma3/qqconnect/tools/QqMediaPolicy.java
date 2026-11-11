package io.mosire.bma3.qqconnect.tools;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Limits for resolving media on demand.
 *
 * <p>Text that fits {@code maxInlineBytes} is returned inside the tool result; anything else is
 * written under {@code dir} and the caller gets a path. The download cap is the hard ceiling —
 * exceeding it fails loudly instead of filling the disk.</p>
 */
public record QqMediaPolicy(Path dir, long maxInlineBytes, long maxDownloadBytes,
                            Duration timeout) {

    public static final long DEFAULT_MAX_INLINE_BYTES = 64L * 1024;
    public static final long DEFAULT_MAX_DOWNLOAD_BYTES = 32L * 1024 * 1024;
    public static final int DEFAULT_TIMEOUT_SECONDS = 20;

    public QqMediaPolicy {
        maxInlineBytes = maxInlineBytes <= 0 ? DEFAULT_MAX_INLINE_BYTES : maxInlineBytes;
        maxDownloadBytes = maxDownloadBytes <= 0 ? DEFAULT_MAX_DOWNLOAD_BYTES : maxDownloadBytes;
        timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS) : timeout;
    }

    public static QqMediaPolicy defaults(Path dataDir) {
        return new QqMediaPolicy(dataDir.resolve("downloads"), DEFAULT_MAX_INLINE_BYTES,
                DEFAULT_MAX_DOWNLOAD_BYTES, Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    }
}
