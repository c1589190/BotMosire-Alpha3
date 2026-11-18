package io.mosire.bma3.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Acceptance for the rolling context copy: A7 idempotence, A8 bounds, A9 truncation, A10 gaps. */
class SqliteContextDigestStoreTest {

    private static final String SESSION = "session-abc";
    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    @TempDir
    Path root;

    private SqliteContextDigestStore store;
    private SqliteContextDigestStore open(Supplier<ContextOptions> options,
            UnaryOperator<String> sanitizer) {
        store = new SqliteContextDigestStore(root.resolve("memory.db"), options, sanitizer);
        return store;
    }

    private static ContextEntry entry(long seq, String kind, String body) {
        return new ContextEntry(seq, kind, "owner", NOW, body);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void a7_same_batch_twice_does_not_duplicate_rows() {
        SqliteContextDigestStore digest = open(ContextOptions::defaults, UnaryOperator.identity());
        List<ContextEntry> batch = List.of(entry(1, "user", "第一句"), entry(2, "assistant", "第二句"));

        digest.append(SESSION, 2, batch, NOW);
        assertThat(digest.crawl(SESSION, 0, 50)).hasSize(2);

        digest.append(SESSION, 2, batch, NOW.plusSeconds(30));
        assertThat(digest.crawl(SESSION, 0, 50)).hasSize(2);
        assertThat(digest.maxEventSeq(SESSION)).hasValue(2);
    }

    @Test
    void a8_eviction_keeps_the_newest_entries() {
        SqliteContextDigestStore digest = open(() -> new ContextOptions(5, 10240, 600),
                UnaryOperator.identity());
        for (long seq = 1; seq <= 8; seq++) {
            digest.append(SESSION, seq, List.of(entry(seq, "user", "第 " + seq + " 条")), NOW);
        }

        List<ContextDigest> kept = digest.recent(SESSION, 50);
        assertThat(kept).hasSize(5);
        assertThat(kept).extracting(ContextDigest::eventSeq).containsExactly(4L, 5L, 6L, 7L, 8L);
    }

    @Test
    void a9_over_long_body_is_truncated_and_flagged() {
        SqliteContextDigestStore digest = open(() -> new ContextOptions(200, 10, 600),
                UnaryOperator.identity());
        SqliteContextDigestStore.AppendResult result =
                digest.append(SESSION, 1, List.of(entry(1, "assistant", "一二三四五六七八九十abcdef")),
                        NOW);

        assertThat(result.truncated()).isEqualTo(1);
        ContextDigest stored = digest.view(SESSION, 1).orElseThrow();
        assertThat(stored.truncated()).isTrue();
        assertThat(stored.chars()).isEqualTo(10);
        assertThat(stored.body()).hasSize(10);
    }

    @Test
    void a10_a_hole_in_the_sequence_is_recorded() {
        SqliteContextDigestStore digest = open(ContextOptions::defaults, UnaryOperator.identity());
        digest.append(SESSION, 2, List.of(entry(1, "user", "一"), entry(2, "assistant", "二")), NOW);
        digest.append(SESSION, 5, List.of(entry(5, "user", "五")), NOW);

        assertThat(digest.gaps(SESSION)).singleElement().satisfies(gap -> {
            assertThat(gap.fromSeq()).isEqualTo(2);
            assertThat(gap.toSeq()).isEqualTo(5);
        });
    }

    @Test
    void a10_replaying_the_missing_range_does_not_clear_the_gap_row() {
        SqliteContextDigestStore digest = open(ContextOptions::defaults, UnaryOperator.identity());
        digest.append(SESSION, 2, List.of(entry(2, "user", "二")), NOW);
        digest.append(SESSION, 5, List.of(entry(5, "user", "五")), NOW);
        digest.append(SESSION, 4, List.of(entry(3, "user", "三"), entry(4, "user", "四")), NOW);

        // The gap stays on record: the copy is auditable, not self-healing.
        assertThat(digest.gaps(SESSION)).hasSize(1);
        assertThat(digest.crawl(SESSION, 0, 50)).extracting(ContextDigest::eventSeq)
                .containsExactly(2L, 3L, 4L, 5L);
    }

    @Test
    void unknown_kind_is_rejected() {
        SqliteContextDigestStore digest = open(ContextOptions::defaults, UnaryOperator.identity());
        assertThatThrownBy(() -> digest.append(SESSION, 1,
                List.of(entry(1, "system", "不该进来")), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind");
        assertThat(digest.crawl(SESSION, 0, 50)).isEmpty();
    }

    @Test
    void bodies_are_redacted_before_they_land() {
        SqliteContextDigestStore digest = open(ContextOptions::defaults,
                text -> text.replace("Bearer abc123", "Bearer ***"));
        digest.append(SESSION, 1,
                List.of(entry(1, "user", "头 Bearer abc123 尾")), NOW);

        assertThat(digest.view(SESSION, 1).orElseThrow().body()).isEqualTo("头 Bearer *** 尾");
    }

    @Test
    void an_empty_push_still_moves_the_watermark() {
        SqliteContextDigestStore digest = open(ContextOptions::defaults, UnaryOperator.identity());
        digest.append(SESSION, 1, List.of(entry(1, "user", "一")), NOW);
        assertThat(digest.lastPushedAt()).contains(NOW);

        digest.append(SESSION, 1, List.of(), NOW.plusSeconds(120));
        assertThat(digest.lastPushedAt()).contains(NOW.plusSeconds(120));
        assertThat(digest.crawl(SESSION, 0, 50)).hasSize(1);
    }

    @Test
    void opening_an_existing_notebook_database_adds_the_context_tables_only() {
        // A P0 install has memory.db with memory_item and nothing else: the context copy must
        // arrive as an addition, never as a rewrite of what is already there.
        SqliteMemoryStore notebook = new SqliteMemoryStore(root.resolve("memory.db"),
                MemoryOptions::defaults, UnaryOperator.identity());
        long id = notebook.write("站会固定在周二", "例会", "agent", null, null, NOW);
        notebook.close();

        SqliteContextDigestStore digest = open(ContextOptions::defaults, UnaryOperator.identity());
        digest.append(SESSION, 1, List.of(entry(1, "user", "第一句")), NOW);

        SqliteMemoryStore reopened = new SqliteMemoryStore(root.resolve("memory.db"),
                MemoryOptions::defaults, UnaryOperator.identity());
        try {
            assertThat(reopened.view(id, NOW, false)).isPresent();
            assertThat(digest.maxEventSeq(SESSION)).hasValue(1);
        } finally {
            reopened.close();
        }
    }

    @Test
    void the_store_survives_a_reopen_and_keeps_the_watermark() {
        SqliteContextDigestStore digest = open(ContextOptions::defaults, UnaryOperator.identity());
        digest.append(SESSION, 3, List.of(entry(3, "tool", "工具结果")), NOW);
        digest.close();

        SqliteContextDigestStore reopened = new SqliteContextDigestStore(
                root.resolve("memory.db"), ContextOptions::defaults, UnaryOperator.identity());
        try {
            assertThat(reopened.maxEventSeq(SESSION)).hasValue(3);
            assertThat(reopened.lastSession()).contains(SESSION);
            assertThat(reopened.exists(SESSION, 3)).isTrue();
            assertThat(reopened.exists(SESSION, 4)).isFalse();
        } finally {
            reopened.close();
        }
    }
}
