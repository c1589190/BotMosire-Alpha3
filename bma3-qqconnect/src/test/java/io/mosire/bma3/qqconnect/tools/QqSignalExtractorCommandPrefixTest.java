package io.mosire.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.qqconnect.onebot.OneBotEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code qq_command} follows the configured prefixes instead of the old hard-coded pair. */
class QqSignalExtractorCommandPrefixTest {

    private static OneBotEvent privateMessage(String text) {
        return new OneBotEvent("message", "private", "", 10001L, 3924945183L, 0L, 42L,
                Instant.parse("2026-11-15T00:00:00Z"), "南沐秋",
                List.of(new Segment("text", text, Map.of())), "");
    }

    @Test
    void dotPrefixOnlyCountsAsACommandWhenItIsConfigured() {
        OneBotEvent dice = privateMessage(".rd 3d6");

        assertThat(QqSignalExtractor.attributes(dice, "肥鱼弗洛洛", false,
                List.of("/", "!")).has(QqSignalExtractor.COMMAND)).isFalse();
        assertThat(QqSignalExtractor.attributes(dice, "肥鱼弗洛洛", false,
                List.of("/", "!", ".")).has(QqSignalExtractor.COMMAND)).isTrue();
    }

    @Test
    void defaultPrefixesStillMatch() {
        assertThat(QqSignalExtractor.attributes(privateMessage("/help"), "肥鱼弗洛洛", false)
                .has(QqSignalExtractor.COMMAND)).isTrue();
        assertThat(QqSignalExtractor.attributes(privateMessage("你好"), "肥鱼弗洛洛", false)
                .has(QqSignalExtractor.COMMAND)).isFalse();
    }
}
