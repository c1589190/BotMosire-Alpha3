package io.mosire.bma3.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PluginHeartbeatTest {

    @Test
    void legacyConstructorDefaultsTokenFacts() {
        PluginHeartbeat heartbeat = new PluginHeartbeat("s", true, "open", 1L, "", "", "",
                Instant.now());

        assertThat(heartbeat.tokenSource()).isEqualTo("unknown");
        assertThat(heartbeat.tokenOk()).isTrue();
    }

    @Test
    void blankTokenSourceIsNormalized() {
        PluginHeartbeat heartbeat = new PluginHeartbeat("s", true, "open", 1L, "", "", "", "  ",
                false, Instant.now());

        assertThat(heartbeat.tokenSource()).isEqualTo("unknown");
        assertThat(heartbeat.tokenOk()).isFalse();
    }
}
