package io.github.c1589190.bma3.qqofficial.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BoundedTextFrameTest {

    @Test
    void fragmentedTextIsBoundedAndOnlyReturnedWhenComplete() {
        BoundedTextFrame frame = new BoundedTextFrame(10);
        assertThat(frame.append("abc", false)).isNull();
        assertThat(frame.append("def", false)).isNull();
        assertThat(frame.append("ghij", true)).isEqualTo("abcdefghij");
    }

    @Test
    void oversizedFrameFailsWithoutIncludingPayloadInTheException() {
        BoundedTextFrame frame = new BoundedTextFrame(8);
        frame.append("1234", false);

        assertThatThrownBy(() -> frame.append("secret-overflow-body", true))
                .isInstanceOf(GatewayFrameTooLargeException.class)
                .hasMessage("FRAME_TOO_LARGE")
                .hasMessageNotContaining("secret-overflow-body");
    }
}
