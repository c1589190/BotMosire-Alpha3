package io.github.c1589190.bma3.qqofficial.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class OfficialQqResponseBodyTest {

    @Test
    void readsAtMostLimitPlusOneBytesAndClosesTheStream() throws Exception {
        CountingStream stream = new CountingStream(new byte[32]);
        assertThatThrownBy(() -> OfficialQqResponseBody.readLimited(stream, 8))
                .isInstanceOf(OfficialQqResponseBody.TooLargeException.class)
                .hasMessageNotContaining("payload");
        assertThat(stream.readBytes).isLessThanOrEqualTo(9);
        assertThat(stream.closed).isTrue();
    }

    @Test
    void acceptsABodyExactlyAtTheLimit() throws Exception {
        byte[] bytes = "12345678".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(OfficialQqResponseBody.readLimited(
                        new ByteArrayInputStream(bytes), bytes.length))
                .containsExactly(bytes);
    }

    private static final class CountingStream extends ByteArrayInputStream {
        private int readBytes;
        private boolean closed;

        private CountingStream(byte[] buffer) {
            super(buffer);
        }

        @Override
        public synchronized int read(byte[] destination, int offset, int length) {
            int read = super.read(destination, offset, length);
            if (read > 0) {
                readBytes += read;
            }
            return read;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
