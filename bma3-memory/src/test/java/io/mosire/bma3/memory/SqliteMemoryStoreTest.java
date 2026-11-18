package io.mosire.bma3.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Storage-level acceptance for the notebook: A1 (schema/migration), A2/A3/A4 (retrieval and tag
 * precedence), A5 (mute and floor) and A13 (redaction before the row is written).
 */
class SqliteMemoryStoreTest {

    private static final Supplier<MemoryOptions> OPTIONS = MemoryOptions::defaults;
    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    @TempDir
    Path root;

    private SqliteMemoryStore open() {
        return open(UnaryOperator.identity());
    }

    private SqliteMemoryStore open(UnaryOperator<String> sanitizer) {
        return new SqliteMemoryStore(root.resolve("memory.db"), OPTIONS, sanitizer);
    }

    @Test
    void a1_schema_is_created_once_and_rows_survive_reopen() {
        long id;
        try (SqliteMemoryStore store = open()) {
            assertThat(store.ping()).isTrue();
            id = store.write("旧库升级后仍可读", "", "agent", "", "", NOW);
        }
        try (SqliteMemoryStore store = open()) {
            assertThat(store.ping()).isTrue();
            assertThat(store.view(id, NOW, false)).isPresent();
            assertThat(store.search("旧库升级", 10, 0)).hasSize(1);
        }
    }

    @Test
    void a2_body_search_hits_every_matching_row() {
        try (SqliteMemoryStore store = open()) {
            store.write("站会固定在周二上午", "", "agent", "", "", NOW);
            store.write("站会纪要发到群里", "", "agent", "", "", NOW);
            store.write("排期表在共享盘", "", "agent", "", "", NOW);
            assertThat(store.search("站会", 10, 0)).hasSize(2);
        }
    }

    @Test
    void a3_two_char_tag_is_found_even_though_fts_cannot_index_it() {
        try (SqliteMemoryStore store = open()) {
            store.write("每周碰一次干活的事", "例会", "agent", "", "", NOW);
            assertThat(store.search("例会", 10, 0)).hasSize(1);
        }
    }

    @Test
    void a2b_two_char_body_query_falls_back_to_the_substring_scan() {
        // FTS5 trigram cannot index substrings below three code points, so the body leg must fall
        // back to LIKE for the two-character Chinese terms this notebook is searched with.
        try (SqliteMemoryStore store = open()) {
            store.write("每周碰一次干活的事", "", "agent", "", "", NOW);
            assertThat(store.search("每周", 10, 0)).hasSize(1);
            assertThat(store.search("每X", 10, 0)).isEmpty();
        }
    }

    @Test
    void a4_tag_hit_outranks_body_hit_even_when_newer() {
        try (SqliteMemoryStore store = open()) {
            long tagged = store.write("每周碰一次", "例会", "agent", "", "", NOW);
            long bodyOnly = store.write("例会就是每周碰一次", "", "agent", "", "",
                    NOW.plusSeconds(60));
            List<MemoryHit> hits = store.search("例会", 10, 0);
            assertThat(hits).hasSize(2);
            assertThat(hits.get(0).id()).isEqualTo(tagged);
            assertThat(hits.get(1).id()).isEqualTo(bodyOnly);
        }
    }

    @Test
    void a5_mute_hides_from_search_but_view_still_reads_the_text() {
        try (SqliteMemoryStore store = open()) {
            long id = store.write("站会固定在周二", "例会", "agent", "", "", NOW);
            assertThat(store.rate(id, MemoryStore.Vote.MUTE, NOW)).isEqualTo(MemoryOptions.MUTED);
            assertThat(store.search("站会", 10, 0)).isEmpty();
            assertThat(store.search("例会", 10, 0)).isEmpty();
            MemoryItem item = store.view(id, NOW, true).orElseThrow();
            assertThat(item.text()).isEqualTo("站会固定在周二");
            assertThat(item.muted()).isTrue();
            assertThat(item.useCount()).isEqualTo(1);
            assertThatThrownBy(() -> store.rate(id, MemoryStore.Vote.USEFUL, NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("静音不可逆");
        }
    }

    @Test
    void a5_useless_clamps_at_configured_floor_and_never_reaches_mute() {
        Supplier<MemoryOptions> floored = () -> new MemoryOptions(20, 10.0, 1.0, -1.0, -0.5, 8000, 1_048_576);
        try (SqliteMemoryStore store = new SqliteMemoryStore(
                root.resolve("memory.db"), floored, UnaryOperator.identity())) {
            long id = store.write("站会固定在周二", "", "agent", "", "", NOW);
            double weight = 0;
            for (int i = 0; i < 5; i++) {
                weight = store.rate(id, MemoryStore.Vote.USELESS, NOW);
            }
            assertThat(weight).isEqualTo(-0.5);
            assertThat(weight).isGreaterThan(-1.0);
        }
    }

    @Test
    void a5_useful_raises_weight() {
        try (SqliteMemoryStore store = open()) {
            long id = store.write("站会固定在周二", "", "agent", "", "", NOW);
            assertThat(store.rate(id, MemoryStore.Vote.USEFUL, NOW)).isEqualTo(1.0);
            assertThat(store.rate(id, MemoryStore.Vote.USEFUL, NOW)).isEqualTo(2.0);
        }
    }

    @Test
    void a13_sanitizer_runs_before_the_row_is_stored() {
        try (SqliteMemoryStore store = open(
                text -> text.replaceAll("Bearer\\s+\\S+", "Bearer [REDACTED]"))) {
            long id = store.write("调用示例 Bearer abc123", "", "agent", "", "", NOW);
            assertThat(store.view(id, NOW, false).orElseThrow().text())
                    .isEqualTo("调用示例 Bearer [REDACTED]");
        }
    }

    @Test
    void search_pages_with_limit_and_offset() {
        try (SqliteMemoryStore store = open()) {
            store.write("站会固定在周二", "", "agent", "", "", NOW);
            store.write("站会纪要发到群里", "", "agent", "", "", NOW);
            store.write("站会改到周三", "", "agent", "", "", NOW);
            List<MemoryHit> first = store.search("站会", 2, 0);
            List<MemoryHit> second = store.search("站会", 2, 2);
            assertThat(first).hasSize(2);
            assertThat(second).hasSize(1);
            assertThat(second.get(0).id()).isNotIn(first.stream().map(MemoryHit::id).toList());
        }
    }
}
