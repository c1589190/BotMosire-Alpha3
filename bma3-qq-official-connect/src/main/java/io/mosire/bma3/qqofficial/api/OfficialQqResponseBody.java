package io.mosire.bma3.qqofficial.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Reads at most {@code maxBytes + 1} response bytes and fails closed when the body exceeds the
 * limit. This avoids buffering an unbounded platform response only to reject it after allocation.
 */
public final class OfficialQqResponseBody {

    public static final int MAX_RESPONSE_BYTES = 1_048_576;

    private OfficialQqResponseBody() {
    }

    public static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes 必须为正");
        }
        try (input) {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream(Math.min(maxBytes + 1, 16 * 1024));
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            while (true) {
                int remaining = maxBytes + 1 - total;
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    return output.toByteArray();
                }
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maxBytes) {
                    throw new TooLargeException();
                }
                output.write(buffer, 0, read);
            }
        }
    }

    /** Fixed-message exception; it never carries response bytes. */
    public static final class TooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        public TooLargeException() {
            super("响应体超过允许上限");
        }
    }
}
