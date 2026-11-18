package io.mosire.bma3.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.memory.ContextDigestStore;
import io.mosire.bma3.memory.ContextEntry;
import io.mosire.bma3.memory.ContextOptions;
import io.mosire.bma3.memory.SqliteContextDigestStore;
import io.mosire.bma3.watchdog.probe.Probes;
import io.mosire.bma3.watchdog.spi.Health;
import io.mosire.bma3.watchdog.spi.ProbeResult;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D-079: the watchdog has teeth only if it can tell "the plugin is alive" from "the copy the Agent
 * reads is still being fed". Every case here is a way those two answers differ.
 */
class ProbesContextTest {

    @TempDir
    Path tempDir;
    private SqliteContextDigestStore store;

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    private ContextDigestStore digest() {
        if (store == null) {
            store = new SqliteContextDigestStore(tempDir.resolve("memory.db"),
                    ContextOptions::defaults, text -> text);
        }
        return store;
    }

    private static ContextEntry entry(long seq, String text) {
        return new ContextEntry(seq, "user", "", Instant.parse("2026-11-18T10:00:00Z"), text);
    }

    private ProbeResult check(String bound, int staleAfterSeconds) {
        return Probes.pluginContext(digest(), () -> bound,
                () -> new ContextOptions(200, 10240, staleAfterSeconds)).check();
    }

    @Test
    void withoutABoundSessionTheCopyIsNotJudged() {
        ProbeResult result = check("", 600);

        assertThat(result.component()).isEqualTo("pluginContext");
        assertThat(result.health()).isEqualTo(Health.UP);
        assertThat(result.detail()).isEqualTo("no bound session");
    }

    @Test
    void aBoundSessionWithNoPushIsDegraded() {
        ProbeResult result = check("session-1", 600);

        assertThat(result.health()).isEqualTo(Health.DEGRADED);
        assertThat(result.code()).isEqualTo("NO_CONTEXT_PUSH");
        assertThat(result.facts()).containsEntry("bound", "session-1");
    }

    @Test
    void aFreshPushIsUp() {
        digest().append("session-1", 2, List.of(entry(1, "a"), entry(2, "b")), Instant.now());

        ProbeResult result = check("session-1", 600);

        assertThat(result.health()).isEqualTo(Health.UP);
        assertThat(result.detail()).isEqualTo("context fresh");
        assertThat(result.facts()).containsEntry("lastSeq", "2");
    }

    @Test
    void aCopyThatStoppedAgeingIsStale() {
        digest().append("session-1", 1, List.of(entry(1, "a")),
                Instant.now().minus(Duration.ofMinutes(30)));

        ProbeResult result = check("session-1", 600);

        assertThat(result.health()).isEqualTo(Health.DEGRADED);
        assertThat(result.code()).isEqualTo("CONTEXT_STALE");
        assertThat(Long.parseLong(result.facts().get("ageSeconds"))).isGreaterThan(600L);
    }

    @Test
    void aHoleInTheSequenceIsAGap() {
        digest().append("session-1", 2, List.of(entry(1, "a"), entry(2, "b")), Instant.now());
        digest().append("session-1", 5, List.of(entry(5, "e")), Instant.now());

        ProbeResult result = check("session-1", 600);

        assertThat(result.health()).isEqualTo(Health.DEGRADED);
        assertThat(result.code()).isEqualTo("CONTEXT_GAP");
        assertThat(result.facts()).containsEntry("gap", "2-5");
        assertThat(result.facts()).containsEntry("gaps", "1");
    }

    @Test
    void aCopyLeftOnAnOldSessionIsAMismatch() {
        digest().append("session-old", 1, List.of(entry(1, "a")), Instant.now());

        ProbeResult result = check("session-1", 600);

        assertThat(result.health()).isEqualTo(Health.DEGRADED);
        assertThat(result.code()).isEqualTo("SESSION_MISMATCH");
        assertThat(result.facts()).containsEntry("pushedSession", "session-old");
        assertThat(result.facts()).containsEntry("bound", "session-1");
    }

    @Test
    void theProbeCarriesNoBodies() {
        String body = "机密正文-do-not-copy";
        digest().append("session-1", 1, List.of(entry(1, body)), Instant.now());

        ProbeResult result = check("session-1", 600);

        assertThat(result.facts().values()).noneMatch(value -> value.contains("机密正文"));
        assertThat(result.detail()).doesNotContain("机密正文");
    }

    @Test
    void withoutAStoreTheProbeIsDisabled() {
        ProbeResult result = Probes.pluginContext(null, () -> "session-1",
                ContextOptions::defaults).check();

        assertThat(result.health()).isEqualTo(Health.UP);
        assertThat(result.detail()).isEqualTo("disabled");
    }
}
