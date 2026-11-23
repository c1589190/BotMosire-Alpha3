package io.github.c1589190.bma3.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
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

    @Test
    void diagnosticsAreOptionalAndNullableSafe() {
        PluginHeartbeat legacy = new PluginHeartbeat("s", true, "open", 1L, "", "", "",
                Instant.now());
        assertThat(legacy.diagnostics()).isEmpty();

        java.util.Map<String, Object> nullable = new java.util.HashMap<>();
        nullable.put("lastError", null);
        PluginHeartbeat withNull = new PluginHeartbeat("s", true, "open", 1L, "", "", "",
                "env-file", true, Instant.now(), nullable);
        assertThat(withNull.diagnostics()).containsKey("lastError");
    }
}
