package io.github.c1589190.bma3.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.c1589190.bma3.core.source.SourceView;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 9: generic single-display-channel projection and evidence-only late updates. */
class InboxChannelProjectionTest {

    @TempDir
    Path tempDir;

    private static final Instant T0 = Instant.parse("2026-11-17T00:00:00Z");

    private InboxService service(SqliteInboxStore store) {
        return new InboxService(store,
                new DeliveryPolicy(300, Duration.ofMillis(1), Duration.ofMillis(1)));
    }

    private static InboxEntry entry(long seq, String channel, String text) {
        return new InboxEntry(seq, T0.plusSeconds(seq), "sender", text, List.of(), channel,
                Map.of("messageId", "m-" + seq));
    }

    private static AppendItem projected(String source, String channel, InboxEntry entry,
                                        int priority, Map<String, Object> summary) {
        return AppendItem.projected(SourceView.of(source, source), entry, priority, T0,
                channel, summary);
    }

    @Test
    void oldDatabaseMigratesInPlaceAndKeepsLegacyRowsAckAndPushRevision() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE inbox_item (
                      id TEXT PRIMARY KEY,
                      source TEXT NOT NULL,
                      source_label TEXT,
                      summary TEXT NOT NULL,
                      entries_json TEXT,
                      total_messages INTEGER,
                      event_revision INTEGER,
                      priority INTEGER NOT NULL,
                      status TEXT NOT NULL,
                      available_at TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT,
                      revision INTEGER NOT NULL,
                      frozen_revision INTEGER NOT NULL DEFAULT 0,
                      continued INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE inbox_meta (
                      key TEXT PRIMARY KEY,
                      value INTEGER NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO inbox_meta(key, value) VALUES "
                    + "('panel_revision', 4), ('push_revision', 9), ('acked_revision', 2)");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO inbox_item (id, source, source_label, summary, entries_json,
                       total_messages, event_revision, priority, status, available_at, created_at,
                       updated_at, revision, frozen_revision, continued)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            insert.setString(1, "legacy-1");
            insert.setString(2, "legacy/source");
            insert.setString(3, "Legacy label");
            insert.setString(4, "legacy summary");
            insert.setString(5, "[{\"localSeq\":3,\"at\":\"2026-11-17T00:00:00Z\","
                    + "\"sender\":\"old\",\"text\":\"legacy body\",\"markers\":[]}]");
            insert.setInt(6, 1);
            insert.setLong(7, 4L);
            insert.setInt(8, 200);
            insert.setString(9, "AVAILABLE");
            insert.setString(10, T0.toString());
            insert.setString(11, T0.toString());
            insert.setString(12, T0.toString());
            insert.setInt(13, 2);
            insert.setLong(14, 0L);
            insert.setInt(15, 0);
            insert.executeUpdate();
        }

        try (SqliteInboxStore store = new SqliteInboxStore(db)) {
            InboxService service = service(store);
            InboxItem migrated = store.item("legacy-1").orElseThrow();
            assertThat(migrated.displayChannel()).isEmpty();
            assertThat(migrated.channelSummary()).isEmpty();
            assertThat(migrated.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.text()).isEqualTo("legacy body");
                assertThat(entry.channel()).isEmpty();
                assertThat(entry.evidence()).isEmpty();
            });
            assertThat(service.panel().revision()).isEqualTo(4);
            assertThat(service.pushRevision()).isEqualTo(9L);
            assertThat(service.ackItems(Set.of("legacy-1"), null).ackedItemIds())
                    .containsExactly("legacy-1");
        }
        try (SqliteInboxStore reopened = new SqliteInboxStore(db)) {
            InboxService service = service(reopened);
            InboxItem migrated = reopened.item("legacy-1").orElseThrow();
            assertThat(migrated.status()).isEqualTo(ItemStatus.ACKED);
            assertThat(migrated.displayChannel()).isEmpty();
            assertThat(migrated.channelSummary()).isEmpty();
            assertThat(service.pushRevision()).isEqualTo(9L);
        }
    }

    @Test
    void oldJsonWithoutChannelFieldsLoadsWithEmptyDefaults() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        List<InboxEntry> entries = mapper.readValue("""
                [{"localSeq":1,"at":"2026-11-17T00:00:00Z","sender":"u",
                  "text":"old","markers":[]}]
                """, new com.fasterxml.jackson.core.type.TypeReference<List<InboxEntry>>() { });
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.channel()).isEmpty();
            assertThat(entry.evidence()).isEmpty();
        });
        InboxItem legacy = new InboxItem("id", "src", "src", "s", entries, 1, 1L, 100,
                ItemStatus.AVAILABLE, T0, T0, T0, 1, 0L, false);
        assertThat(legacy.displayChannel()).isEmpty();
        assertThat(legacy.channelSummary()).isEmpty();
        PanelItem oldPanel = new PanelItem(1, "id", "src", "src", 100, entries, 1, 0,
                ItemStatus.AVAILABLE, T0, false, 0, 0L, false);
        assertThat(oldPanel.displayChannel()).isEmpty();
        assertThat(oldPanel.channelSummary()).isEmpty();
    }

    @Test
    void oneOpenItemKeepsItsDisplayChannelAndRejectsBodyFromAnotherChannel() {
        String source = "qq_thread/abc";
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("multi.db"))) {
            InboxService service = service(store);
            InboxItem first = service.appendProjected(projected(source, "onebot",
                    entry(1, "onebot", "onebot body"), 100,
                    Map.of("onebot", 1)));
            assertThat(first.displayChannel()).isEqualTo("onebot");
            assertThat(first.entries()).singleElement()
                    .extracting(InboxEntry::channel).isEqualTo("onebot");

            InboxItem merged = service.appendProjected(projected(source, "onebot",
                    entry(2, "onebot", "onebot body 2"), 200,
                    Map.of("matched", 0)));
            assertThat(merged.id()).isEqualTo(first.id());
            assertThat(merged.displayChannel()).isEqualTo("onebot");
            assertThat(merged.entries()).hasSize(2);
            assertThat(merged.priority()).isEqualTo(200);

            InboxItem evidence = service.appendProjected(projected(source, "qq_official",
                    entry(3, "qq_official", "official body must not enter panel"),
                    900, Map.of("conflict", 1)));
            assertThat(evidence.id()).isEqualTo(first.id());
            assertThat(evidence.displayChannel()).isEqualTo("onebot");
            assertThat(evidence.entries()).extracting(InboxEntry::text)
                    .containsExactly("onebot body", "onebot body 2");
            assertThat(evidence.summary()).doesNotContain("official body");
            assertThat(evidence.priority()).isEqualTo(200);
            assertThat(evidence.channelSummary()).containsEntry("conflict", 1);
            @SuppressWarnings("unchecked")
            Map<String, Object> observed =
                    (Map<String, Object>) evidence.channelSummary().get("observedByChannel");
            assertThat(observed).containsEntry("qq_official", 1L);
            assertThat(store.items()).hasSize(1);

            InboxItem third = service.appendProjected(projected(source, "onebot",
                    entry(4, "onebot", "onebot body 3"), 50, Map.of()));
            assertThat(third.id()).isEqualTo(first.id());
            assertThat(third.entries()).hasSize(3);
            assertThat(store.items()).hasSize(1);
        }
    }

    @Test
    void newBodyAfterFreezeCanChooseADifferentDisplayChannelForFallback() {
        String source = "qq_thread/no-open";
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("no-open.db"))) {
            InboxService service = service(store);
            InboxItem first = service.appendProjected(projected(source, "onebot",
                    entry(1, "onebot", "display body"), 100, Map.of()));
            InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(1));
            assertThat(pushed).isNotNull();
            long pushedAt = pushed.pushRevision();
            service.ackItems(Set.of(first.id()), null);
            long revisionBeforeNext = service.panel().revision();

            // The preferred/onebot channel is now unavailable: the app explicitly selects
            // qq_official for the next message. The old frozen/ACK item must not dictate this.
            InboxItem second = service.appendProjected(projected(source, "qq_official",
                    entry(2, "qq_official", "fallback body"), 500, Map.of("qq_official", 1)));
            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(second.displayChannel()).isEqualTo("qq_official");
            assertThat(second.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.channel()).isEqualTo("qq_official");
                assertThat(entry.text()).isEqualTo("fallback body");
            });
            assertThat(second.continued()).isFalse();
            assertThat(service.panel().revision()).isGreaterThan(revisionBeforeNext);
            assertThat(store.items()).hasSize(2);
            assertThat(service.pushRevision()).isEqualTo(pushedAt);
            assertThat(store.item(first.id()).orElseThrow().displayChannel())
                    .isEqualTo("onebot");

            // Freeze the fallback item too, then prove late evidence cannot schedule another wake.
            assertThat(service.awaitPush(service.pushRevision(), Duration.ofSeconds(1)))
                    .isNotNull();
            long pushBeforeEvidence = service.pushRevision();

            // Late evidence for the already-frozen first message is explicit and itemId-addressed.
            InboxItem evidence = service.appendEvidenceOnly(InboxEvidenceUpdate.keyedItem(
                    first.id(), "late-qq-official-1", "qq_official",
                    Map.of("observationId", "obs-late-1", "state", "MATCHED")));
            assertThat(evidence.id()).isEqualTo(first.id());
            assertThat(evidence.entries()).hasSize(1);
            assertThat(evidence.displayChannel()).isEqualTo("onebot");
            assertThat(evidence.channelSummary().toString()).contains("obs-late-1");
            assertThat(store.item(second.id()).orElseThrow().entries()).hasSize(1);
            assertThat(store.items()).hasSize(2);
            assertThat(service.pushRevision()).isEqualTo(pushBeforeEvidence);
            assertThat(service.awaitPush(pushBeforeEvidence, Duration.ofMillis(120))).isNull();
        }
    }

    @Test
    void selectedAndBodyChannelMismatchFailsClosedBeforeCreatingAnItem() {
        String source = "qq_thread/mismatch";
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("mismatch.db"))) {
            InboxService service = service(store);
            AppendItem mismatch = projected(source, "onebot",
                    entry(1, "qq_official", "body says another channel"), 200, Map.of());

            assertThatThrownBy(() -> service.appendProjected(mismatch))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CHANNEL_MISMATCH");
            assertThat(store.items()).isEmpty();
            assertThat(service.panel().revision()).isZero();

            // Same mismatch through the idempotent path must roll back the receipt, so a corrected
            // retry under the same request key is still allowed to create the item.
            assertThatThrownBy(() -> service.appendProjectedOnce("mismatch-key", mismatch))
                    .hasMessageContaining("CHANNEL_MISMATCH");
            InboxItem corrected = service.appendProjectedOnce("mismatch-key",
                    projected(source, "onebot", entry(1, "onebot", "correct"), 200, Map.of()));
            assertThat(corrected).isNotNull();
            assertThat(corrected.displayChannel()).isEqualTo("onebot");
            assertThat(store.items()).hasSize(1);

            // An active item also rejects a contradictory selected/body pair instead of silently
            // turning it into evidence for the wrong body channel.
            InboxItem active = store.activeForSource(source).orElseThrow();
            assertThatThrownBy(() -> service.appendProjected(projected(source, "onebot",
                    entry(2, "qq_official", "must not become onebot"), 300, Map.of())))
                    .hasMessageContaining("CHANNEL_MISMATCH");
            assertThat(store.item(active.id()).orElseThrow().entries()).hasSize(1);
            assertThat(store.items()).hasSize(1);
        }
    }

    @Test
    void frozenItemAcceptsEvidenceWithoutChangingBodyPriorityOrPushWatermark() throws Exception {
        Path db = tempDir.resolve("frozen.db");
        String source = "qq_thread/frozen";
        String itemId;
        long pushRevision;
        long panelRevision;
        Instant updatedAt;
        int revision;
        long frozenRevision;
        try (SqliteInboxStore store = new SqliteInboxStore(db)) {
            InboxService service = service(store);
            InboxItem item = service.appendProjected(projected(source, "onebot",
                    entry(1, "onebot", "frozen body"), 100,
                    Map.of("onebot", 1)));
            itemId = item.id();
            InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(1));
            assertThat(pushed).isNotNull();
            pushRevision = pushed.pushRevision();
            InboxItem frozen = store.item(itemId).orElseThrow();
            panelRevision = pushed.revision();
            updatedAt = frozen.updatedAt();
            revision = frozen.revision();
            frozenRevision = frozen.frozenRevision();

            InboxEvidenceUpdate late = InboxEvidenceUpdate.matched(itemId, "", "qq_official",
                    Map.of("observationId", "obs-2", "messageId", "m-2"),
                    Map.of("channelOnly", 1), 900);
            InboxItem updated = service.appendEvidenceOnly(late);

            assertThat(updated.entries()).isEqualTo(frozen.entries());
            assertThat(updated.summary()).isEqualTo(frozen.summary());
            assertThat(updated.priority()).isEqualTo(100);
            assertThat(updated.updatedAt()).isEqualTo(updatedAt);
            assertThat(updated.revision()).isEqualTo(revision);
            assertThat(updated.frozenRevision()).isEqualTo(frozenRevision);
            assertThat(updated.channelSummary()).containsEntry("channelOnly", 1);
            assertThat(updated.channelSummary().toString()).contains("obs-2");
            assertThat(service.pushRevision()).isEqualTo(pushRevision);
            assertThat(service.panel().revision()).isEqualTo(panelRevision);
            assertThat(service.awaitPush(pushRevision, Duration.ofMillis(120))).isNull();
            assertThatThrownBy(() -> service.appendEvidenceOnly("itm-missing", "qq_official",
                    Map.of("x", 1))).hasMessageContaining("ITEM_NOT_FOUND");
        }

        try (SqliteInboxStore reopened = new SqliteInboxStore(db)) {
            InboxService service = service(reopened);
            InboxItem persisted = service.item(itemId);
            assertThat(persisted.displayChannel()).isEqualTo("onebot");
            assertThat(persisted.channelSummary().toString()).contains("obs-2", "qq_official");
            assertThat(persisted.entries()).hasSize(1);
            assertThat(service.pushRevision()).isEqualTo(pushRevision);
        }
    }

    @Test
    void openItemCanRaisePriorityOnlyForExplicitMatchedEvidence() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("open-evidence.db"))) {
            InboxService service = service(store);
            InboxItem item = service.appendProjected(projected("qq_thread/open", "onebot",
                    entry(1, "onebot", "open body"), 100, Map.of()));

            InboxItem notMatched = service.appendEvidenceOnly(InboxEvidenceUpdate.forItem(
                    item.id(), "qq_official", Map.of("priority", 500, "state", "CHANNEL_ONLY")));
            assertThat(notMatched.priority()).isEqualTo(100);

            InboxItem matched = service.appendEvidenceOnly(InboxEvidenceUpdate.matched(
                    item.id(), "", "qq_official",
                    Map.of("priority", 500, "state", "MATCHED"), Map.of(), 500));
            assertThat(matched.priority()).isEqualTo(500);
            assertThat(matched.entries()).hasSize(1);
            assertThat(matched.summary()).isEqualTo(item.summary());
        }
    }

    @Test
    void sameDisplayChannelAfterFreezeStillOpensANewContinuedItem() {
        String source = "qq_thread/continued";
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("continued.db"))) {
            InboxService service = service(store);
            InboxItem first = service.appendProjected(projected(source, "onebot",
                    entry(1, "onebot", "first"), 300, Map.of()));
            assertThat(service.awaitPush(service.pushRevision(), Duration.ofSeconds(1)))
                    .isNotNull();

            InboxItem second = service.appendProjected(projected(source, "onebot",
                    entry(2, "onebot", "second"), 300, Map.of()));
            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(second.continued()).isTrue();
            assertThat(second.displayChannel()).isEqualTo("onebot");
            assertThat(second.entries()).singleElement()
                    .extracting(InboxEntry::text).isEqualTo("second");
            assertThat(store.items()).hasSize(2);
        }
    }

    @Test
    void projectedOnceIsIdempotentAcrossAckRestartAndCommittedItemDeletion() throws Exception {
        Path db = tempDir.resolve("once.db");
        String requestKey = "evt-1:canonical-append";
        String source = "qq_thread/once";
        AppendItem request = projected(source, "onebot",
                entry(1, "onebot", "only once"), 250, Map.of("onebot", 1));
        String itemId;
        long firstPanelRevision;

        try (SqliteInboxStore store = new SqliteInboxStore(db)) {
            InboxService service = service(store);
            InboxItem first = service.appendProjectedOnce(requestKey, request);
            assertThat(first).isNotNull();
            itemId = first.id();
            firstPanelRevision = service.panel().revision();
            assertThat(firstPanelRevision).isPositive();

            // Repeat with a deliberately different body: the key wins and nothing is appended.
            InboxItem replay = service.appendProjectedOnce(requestKey, projected(source, "onebot",
                    entry(2, "onebot", "must not appear"), 900, Map.of("replay", 1)));
            assertThat(replay.id()).isEqualTo(itemId);
            assertThat(replay.entries()).extracting(InboxEntry::text).containsExactly("only once");
            assertThat(service.panel().revision()).isEqualTo(firstPanelRevision);
            assertThat(store.items()).hasSize(1);

            service.ackItems(Set.of(itemId), null);
            long afterAckRevision = service.panel().revision();
            InboxItem replayAfterAck = service.appendProjectedOnce(requestKey, request);
            assertThat(replayAfterAck.id()).isEqualTo(itemId);
            assertThat(replayAfterAck.status()).isEqualTo(ItemStatus.ACKED);
            assertThat(service.panel().revision()).isEqualTo(afterAckRevision);
            assertThat(store.items()).hasSize(1);
        }

        // Simulated process restart after the receipt/item/revision commit.
        try (SqliteInboxStore reopened = new SqliteInboxStore(db)) {
            InboxService service = service(reopened);
            long revisionBefore = service.panel().revision();
            long pushBefore = service.pushRevision();
            InboxItem replay = service.appendProjectedOnce(requestKey, request);
            assertThat(replay.id()).isEqualTo(itemId);
            assertThat(replay.status()).isEqualTo(ItemStatus.ACKED);
            assertThat(service.panel().revision()).isEqualTo(revisionBefore);
            assertThat(service.pushRevision()).isEqualTo(pushBefore);
            assertThat(reopened.items()).hasSize(1);

            // Simulated retention deletion after the commit: the receipt must remain and keep
            // suppressing injection forever, even though no item can be returned.
            assertThat(reopened.purge(Instant.now().plusSeconds(3600), 0)).isEqualTo(1);
            long afterDeleteRevision = service.panel().revision();
            InboxItem afterDelete = service.appendProjectedOnce(requestKey, request);
            assertThat(afterDelete).isNull();
            assertThat(reopened.items()).isEmpty();
            assertThat(service.panel().revision()).isEqualTo(afterDeleteRevision);
        }

        // The deleted-item receipt also survives another restart.
        try (SqliteInboxStore reopenedAgain = new SqliteInboxStore(db)) {
            InboxService service = service(reopenedAgain);
            long revisionBefore = service.panel().revision();
            assertThat(service.appendProjectedOnce(requestKey, request)).isNull();
            assertThat(reopenedAgain.items()).isEmpty();
            assertThat(service.panel().revision()).isEqualTo(revisionBefore);
        }
    }

    @Test
    void evidenceOnlyReplayUsesKeyedDedupeAndUnkeyedSnapshotReplacement() throws Exception {
        Path db = tempDir.resolve("evidence-replay.db");
        String itemId;
        try (SqliteInboxStore store = new SqliteInboxStore(db)) {
            InboxService service = service(store);
            InboxItem item = service.appendProjected(projected("qq_thread/evidence-replay",
                    "onebot", entry(1, "onebot", "body"), 100, Map.of()));
            itemId = item.id();

            InboxEvidenceUpdate firstEvidence = InboxEvidenceUpdate.keyedItem(itemId, "obs-1",
                    "qq_official", Map.of("observationId", "obs-1"));
            service.appendEvidenceOnly(firstEvidence);
            InboxItem one = service.appendEvidenceOnly(firstEvidence);
            assertThat(one.priority()).isEqualTo(100);
            assertThat(one.entries()).hasSize(1);
            assertThat(channelCount(one.channelSummary(), "qq_official")).isEqualTo(1L);
            assertThat(evidenceRows(one.channelSummary())).hasSize(1);

            InboxEvidenceUpdate secondEvidence = InboxEvidenceUpdate.keyedItem(itemId, "obs-2",
                    "qq_official", Map.of("observationId", "obs-2"));
            InboxItem two = service.appendEvidenceOnly(secondEvidence);
            assertThat(channelCount(two.channelSummary(), "qq_official")).isEqualTo(2L);
            assertThat(evidenceRows(two.channelSummary())).hasSize(2);

            // Unkeyed calls are absolute snapshots: replacing twice does not inflate.
            service.appendEvidenceOnly(itemId, "qq_official", Map.of("observationId", "snap-1"));
            InboxItem snapshot = service.appendEvidenceOnly(itemId, "qq_official",
                    Map.of("observationId", "snap-1"));
            assertThat(channelCount(snapshot.channelSummary(), "qq_official")).isEqualTo(1L);
            assertThat(evidenceRows(snapshot.channelSummary())).hasSize(1);
        }

        try (SqliteInboxStore reopened = new SqliteInboxStore(db)) {
            InboxService service = service(reopened);
            Map<String, Object> before = service.item(itemId).channelSummary();

            // Same key after restart is a complete no-op.
            InboxItem replay = service.appendEvidenceOnly(InboxEvidenceUpdate.keyedItem(itemId,
                    "obs-1", "qq_official", Map.of("observationId", "different")));
            assertThat(replay.channelSummary()).isEqualTo(before);
            assertThat(channelCount(replay.channelSummary(), "qq_official")).isEqualTo(1L);

            // A new key still works after restart and increments exactly once.
            InboxItem three = service.appendEvidenceOnly(InboxEvidenceUpdate.keyedItem(itemId,
                    "obs-3", "qq_official", Map.of("observationId", "obs-3")));
            assertThat(channelCount(three.channelSummary(), "qq_official")).isEqualTo(2L);
            assertThat(evidenceRows(three.channelSummary())).hasSize(2);
        }
    }

    @Test
    void matchedEvidencePriorityRiseWakesAwaitPushToRecalculateDue() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("wake.db"))) {
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(300, Duration.ofMillis(10), Duration.ofSeconds(5)));
            InboxItem item = service.appendProjected(projected("qq_thread/wake", "onebot",
                    entry(1, "onebot", "open body"), 100, Map.of()));
            long panelRevision = service.panel().revision();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<InboxPanel> waiting = pool.submit(() -> service.awaitPush(
                        service.pushRevision(), Duration.ofSeconds(3)));
                Thread.sleep(150);
                assertThat(waiting.isDone()).as("waiting on old normalDelay").isFalse();

                InboxItem raised = service.appendEvidenceOnly(InboxEvidenceUpdate.matched(
                        item.id(), "", "qq_official", Map.of("state", "MATCHED"),
                        Map.of(), 300));
                assertThat(raised.priority()).isEqualTo(300);
                assertThat(service.panel().revision())
                        .as("evidence priority change must not bump panel revision")
                        .isEqualTo(panelRevision);

                InboxPanel pushed = waiting.get(2, TimeUnit.SECONDS);
                assertThat(pushed).isNotNull();
                assertThat(pushed.items()).singleElement()
                        .extracting(PanelItem::priority).isEqualTo(300);
                assertThat(service.pushRevision()).isPositive();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void evidenceKeysUseBoundedSummaryAndPersistAcrossRestart() throws Exception {
        Path db = tempDir.resolve("evidence-many.db");
        String itemId;
        try (SqliteInboxStore store = new SqliteInboxStore(db)) {
            InboxService service = service(store);
            InboxItem item = service.appendProjected(projected("qq_thread/many", "onebot",
                    entry(1, "onebot", "body"), 100, Map.of()));
            itemId = item.id();
            // Batch the 10k evidence updates into one outer test transaction; production callers
            // normally use one transaction per event. This keeps the stress case fast while still
            // exercising the persistent per-item key table and bounded summary serialization.
            store.inTransaction(() -> {
                for (int i = 0; i < 10_000; i++) {
                    service.appendEvidenceOnly(InboxEvidenceUpdate.keyedItem(itemId, "k-" + i,
                            "qq_official", Map.of("observationId", "obs-" + i)));
                }
            });
            InboxItem current = service.item(itemId);
            assertThat(current.channelSummary()).doesNotContainKey("evidenceKeys");
            assertThat(channelCount(current.channelSummary(), "qq_official")).isEqualTo(10_000L);
            assertThat(evidenceRows(current.channelSummary())).hasSize(64);
            String summaryJson = new ObjectMapper().writeValueAsString(current.channelSummary());
            assertThat(summaryJson.length()).isLessThan(20_000);

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                 Statement statement = connection.createStatement();
                 var result = statement.executeQuery(
                         "SELECT COUNT(*) FROM inbox_evidence_key")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(10_000);
            }
        }

        try (SqliteInboxStore reopened = new SqliteInboxStore(db)) {
            InboxService service = service(reopened);
            Map<String, Object> before = service.item(itemId).channelSummary();
            InboxItem replay = service.appendEvidenceOnly(InboxEvidenceUpdate.keyedItem(itemId,
                    "k-0", "qq_official", Map.of("observationId", "different")));
            assertThat(replay.channelSummary()).isEqualTo(before);
            assertThat(channelCount(replay.channelSummary(), "qq_official")).isEqualTo(10_000L);

            InboxItem next = service.appendEvidenceOnly(InboxEvidenceUpdate.keyedItem(itemId,
                    "k-10000", "qq_official", Map.of("observationId", "obs-10000")));
            assertThat(channelCount(next.channelSummary(), "qq_official")).isEqualTo(10_001L);
            assertThat(evidenceRows(next.channelSummary())).hasSize(64);
        }
    }

    @Test
    void unkeyedEvidenceTreatsCallerAggregateAsAbsoluteSnapshot() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("snapshot.db"))) {
            InboxService service = service(store);
            InboxItem item = service.appendProjected(projected("qq_thread/snapshot", "onebot",
                    entry(1, "onebot", "body"), 100, Map.of()));
            Map<String, Object> absolute = Map.of(
                    "observedByChannel", Map.of("qq_official", 7L),
                    "evidence", List.of(Map.of("observationId", "absolute-1")));
            InboxEvidenceUpdate snapshot = new InboxEvidenceUpdate(item.id(), "", "qq_official",
                    Map.of(), absolute, null, false, T0, "");
            service.appendEvidenceOnly(snapshot);
            InboxItem again = service.appendEvidenceOnly(snapshot);
            assertThat(channelCount(again.channelSummary(), "qq_official")).isEqualTo(7L);
            assertThat(evidenceRows(again.channelSummary())).hasSize(1);
        }
    }

    @Test
    void receiptBindingRejectsSourceOrChannelConflictsEvenAfterRetention() throws Exception {
        Path db = tempDir.resolve("receipt-binding.db");
        String key = "receipt-binding-key";
        String source = "qq_thread/receipt-a";
        try (SqliteInboxStore store = new SqliteInboxStore(db)) {
            InboxService service = service(store);
            InboxItem first = service.appendProjectedOnce(key, projected(source, "onebot",
                    entry(1, "onebot", "body"), 200, Map.of()));
            long revision = service.panel().revision();
            InboxStore.Receipt receipt = store.receipt(key).orElseThrow();
            assertThat(receipt.source()).isEqualTo(source);
            assertThat(receipt.channel()).isEqualTo("onebot");
            assertThat(receipt.itemId()).isEqualTo(first.id());

            assertThatThrownBy(() -> service.appendProjectedOnce(key,
                    projected("qq_thread/receipt-b", "onebot",
                            entry(2, "onebot", "other source"), 200, Map.of())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("RECEIPT_CONFLICT");
            assertThat(store.items()).hasSize(1);
            assertThat(service.panel().revision()).isEqualTo(revision);

            assertThatThrownBy(() -> service.appendProjectedOnce(key,
                    projected(source, "qq_official",
                            entry(3, "qq_official", "other channel"), 200, Map.of())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("RECEIPT_CONFLICT");
            assertThat(store.items()).hasSize(1);
            assertThat(service.panel().revision()).isEqualTo(revision);

            service.ackItems(Set.of(first.id()), null);
            assertThat(store.purge(Instant.now().plusSeconds(3600), 0)).isEqualTo(1);
            assertThatThrownBy(() -> service.appendProjectedOnce(key,
                    projected("qq_thread/receipt-b", "onebot",
                            entry(4, "onebot", "still conflicting"), 200, Map.of())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("RECEIPT_CONFLICT");
            assertThat(service.appendProjectedOnce(key, projected(source, "onebot",
                    entry(5, "onebot", "same binding after purge"), 200, Map.of()))).isNull();
            assertThat(store.items()).isEmpty();
        }
    }

    @Test
    void evidenceItemIdAndSourceMustAgree() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("source-check.db"))) {
            InboxService service = service(store);
            InboxItem item = service.appendProjected(projected("qq_thread/source-check",
                    "onebot", entry(1, "onebot", "body"), 100, Map.of()));
            Map<String, Object> before = item.channelSummary();
            assertThatThrownBy(() -> service.appendEvidenceOnly(new InboxEvidenceUpdate(
                    item.id(), "qq_thread/wrong", "qq_official",
                    Map.of("observationId", "bad"), Map.of(), null, false, T0, "")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SOURCE_MISMATCH");
            assertThat(service.item(item.id()).channelSummary()).isEqualTo(before);

            InboxItem accepted = service.appendEvidenceOnly(new InboxEvidenceUpdate(
                    item.id(), item.source(), "qq_official",
                    Map.of("observationId", "good"), Map.of(), null, false, T0, ""));
            assertThat(channelCount(accepted.channelSummary(), "qq_official")).isEqualTo(1L);
        }
    }

    @Test
    void keyedEvidenceRejectsReservedSummaryOverridesAndKeepsCrossChannelCounts() throws Exception {
        Path db = tempDir.resolve("keyed-reserved.db");
        String itemId;
        try (SqliteInboxStore store = new SqliteInboxStore(db)) {
            InboxService service = service(store);
            InboxItem item = service.appendProjected(projected("qq_thread/keyed-reserved",
                    "onebot", entry(1, "onebot", "body"), 100, Map.of()));
            itemId = item.id();

            service.appendEvidenceOnly(new InboxEvidenceUpdate(itemId, "", "onebot",
                    Map.of("observationId", "a-1"), Map.of("matched", 1),
                    null, false, T0, "a-1"));
            service.appendEvidenceOnly(new InboxEvidenceUpdate(itemId, "", "qq_official",
                    Map.of("observationId", "b-1"), Map.of("conflict", 1),
                    null, false, T0, "b-1"));

            Map<String, Object> before = service.item(itemId).channelSummary();
            for (String field : List.of("observedByChannel", "evidence", "evidenceKeys",
                    "lastEvidenceAt")) {
                Map<String, Object> reserved = Map.of(field,
                        field.equals("evidenceKeys") ? Map.of("forged", T0.toString())
                                : List.of(Map.of("forged", true)));
                assertThatThrownBy(() -> service.appendEvidenceOnly(new InboxEvidenceUpdate(
                        itemId, "", "onebot", Map.of("observationId", "bad"),
                        reserved, null, false, T0, "bad-" + field)))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("EVIDENCE_SUMMARY_RESERVED_FIELD")
                        .hasMessageContaining(field);
            }
            assertThat(service.item(itemId).channelSummary()).isEqualTo(before);

            InboxItem valid = service.appendEvidenceOnly(new InboxEvidenceUpdate(
                    itemId, "", "onebot", Map.of("observationId", "a-2"),
                    Map.of("conflict", 2), null, false, T0, "a-2"));
            assertThat(channelCount(valid.channelSummary(), "onebot")).isEqualTo(2L);
            assertThat(channelCount(valid.channelSummary(), "qq_official")).isEqualTo(1L);
            assertThat(evidenceRows(valid.channelSummary())).hasSize(3);
            assertThat(valid.channelSummary()).containsEntry("conflict", 2);
        }

        try (SqliteInboxStore reopened = new SqliteInboxStore(db)) {
            InboxService service = service(reopened);
            Map<String, Object> before = service.item(itemId).channelSummary();
            InboxItem replay = service.appendEvidenceOnly(new InboxEvidenceUpdate(
                    itemId, "", "onebot", Map.of("observationId", "a-1"),
                    Map.of("matched", 99), null, false, T0, "a-1"));
            assertThat(replay.channelSummary()).isEqualTo(before);
            assertThat(channelCount(replay.channelSummary(), "onebot")).isEqualTo(2L);
            assertThat(channelCount(replay.channelSummary(), "qq_official")).isEqualTo(1L);
            assertThat(evidenceRows(replay.channelSummary())).hasSize(3);
        }
    }

    @Test
    void activeSourceIndexAndLatestLookupAreSqlBounded() throws Exception {
        Path db = tempDir.resolve("indexes.db");
        try (SqliteInboxStore store = new SqliteInboxStore(db)) {
            InboxService service = service(store);
            String source = "qq_thread/index";
            service.appendProjected(projected(source, "onebot",
                    entry(1, "onebot", "first"), 100, Map.of()));
            assertThat(service.awaitPush(service.pushRevision(), Duration.ofSeconds(1)))
                    .isNotNull();
            InboxItem second = service.appendProjected(projected(source, "onebot",
                    entry(2, "onebot", "second"), 100, Map.of()));
            assertThat(store.latestForSource(source).orElseThrow().id()).isEqualTo(second.id());

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "PRAGMA index_info(idx_inbox_item_active_source)")) {
                List<String> columns = new ArrayList<>();
                while (result.next()) {
                    columns.add(result.getString("name"));
                }
                assertThat(columns).containsExactly(
                        "source", "status", "frozen_revision", "created_at");
            }

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                 PreparedStatement statement = connection.prepareStatement("""
                         EXPLAIN QUERY PLAN
                         SELECT * FROM inbox_item
                         WHERE source = ?
                         ORDER BY created_at DESC, updated_at DESC, id DESC
                         LIMIT 1
                         """)) {
                statement.setString(1, source);
                try (ResultSet result = statement.executeQuery()) {
                    StringBuilder plan = new StringBuilder();
                    while (result.next()) {
                        plan.append(result.getString("detail")).append('\n');
                    }
                    assertThat(plan.toString()).contains("idx_inbox_item_latest_source");
                    assertThat(plan.toString()).doesNotContain("TEMP B-TREE");
                }
            }
        }
    }

    private static long channelCount(Map<String, Object> summary, String channel) {
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) summary.get("observedByChannel");
        Object value = counts == null ? null : counts.get(channel);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static List<?> evidenceRows(Map<String, Object> summary) {
        Object value = summary.get("evidence");
        return value instanceof List<?> list ? list : List.of();
    }

    @Test
    void legacyOpenItemAdoptsItsFirstProjectedDisplayChannel() {
        String source = "legacy/adopt";
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("adopt.db"))) {
            InboxService service = service(store);
            InboxItem legacy = service.appendOrMerge(
                    new AppendItem(source, "legacy body", 120, T0));
            assertThat(legacy.displayChannel()).isEmpty();

            InboxItem projected = service.appendProjected(projected(source, "onebot",
                    entry(2, "onebot", "projected body"), 130, Map.of()));
            assertThat(projected.id()).isEqualTo(legacy.id());
            assertThat(projected.displayChannel()).isEqualTo("onebot");
            assertThat(projected.entries()).singleElement()
                    .extracting(InboxEntry::channel).isEqualTo("onebot");
            assertThat(store.items()).hasSize(1);
        }
    }

    @Test
    void legacyAppendPathAndPanelShapeRemainBackwardCompatible() {
        String source = "legacy/source";
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("legacy-path.db"))) {
            InboxService service = service(store);
            AppendItem legacy = new AppendItem(source, "legacy summary", 120, T0);
            InboxItem first = service.appendOrMerge(legacy);
            InboxItem second = service.appendOrMerge(legacy);
            assertThat(second.id()).isEqualTo(first.id());
            assertThat(second.totalMessages()).isEqualTo(2);
            assertThat(second.displayChannel()).isEmpty();
            assertThat(second.channelSummary()).isEmpty();
            InboxPanel panel = service.panel();
            assertThat(panel.items()).singleElement().satisfies(item -> {
                assertThat(item.displayChannel()).isEmpty();
                assertThat(item.channelSummary()).isEmpty();
            });
        }
    }
}
