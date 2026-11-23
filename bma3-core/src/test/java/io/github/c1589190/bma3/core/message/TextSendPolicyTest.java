package io.github.c1589190.bma3.core.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextSendPolicyTest {

    @Test
    void countsCodepointsAndAcceptsExactly3000() {
        TextSendPolicy policy = TextSendPolicy.defaults();

        assertThat(policy.count("😀".repeat(3000))).isEqualTo(3000);
        assertThat(policy.chunks("😀".repeat(3000))).hasSize(1);
        assertThat(policy.chunks("😀".repeat(3000)).getFirst()).isEqualTo("😀".repeat(3000));
    }

    @Test
    void rejectFailsAt3001WithoutTruncating() {
        TextSendPolicy policy = TextSendPolicy.defaults();

        assertThatThrownBy(() -> policy.chunks("😀".repeat(3001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("TEXT_TOO_LONG")
                .hasMessageContaining("actual=3001")
                .hasMessageContaining("max=3000");
    }

    @Test
    void splitIsCodepointSafeAtTheBoundary() {
        String text = "x".repeat(2999) + "😀";

        List<String> chunks = TextSendPolicy.from(3000, "split").chunks(text + "y");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst()).isEqualTo(text);
        assertThat(chunks.get(1)).isEqualTo("y");
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.codePointCount(0, chunk.length())).isLessThanOrEqualTo(3000));
    }

    @Test
    void splitRejectsInvalidPolicyValues() {
        assertThatThrownBy(() -> new TextSendPolicy(0, "reject"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextSendPolicy(3001, "reject"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextSendPolicy(10, "truncate"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
