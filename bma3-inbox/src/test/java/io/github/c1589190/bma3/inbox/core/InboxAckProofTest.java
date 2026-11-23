package io.github.c1589190.bma3.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.bma3.core.source.SourceView;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Durable database identity, complete per-item ACK proof and atomic ACK writes.
 *
 * <p>All tests use a real temporary SQLite database. The only fake is an injected write fault used
 * to prove that a mid-batch database failure rolls back every row; it proves nothing about a real
 * QQ or DSH host.</p>
 */
class InboxAckProofTest {

    private static final DeliveryPolicy POLICY =
            new DeliveryPolicy(300, Duration.ofMillis(80), Duration.ofMillis(120));

    @TempDir
    Path tempDir;

    private static InboxService service(InboxStore store) {
        return new InboxService(store, POLICY, "bma3", () -> new InboxRuntime(6, 6, true));
    }

    private static void append(InboxService service, String source, String text) {
        service.append(AppendItem.of(SourceView.of(source, source),
                new InboxEntry(1L, Instant.now(), "u", text, List.of()), 300, Instant.now()));
    }

    /** Appends one item and pushes it, so it is frozen (i.e. a plugin could have displayed it). */
    private static InboxPanel appendAndPush(InboxService service, String source, String text) {
        append(service, source, text);
        InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        assertThat(pushed).as("推送必须发生").isNotNull();
        return pushed;
    }

    // ------------------------------------------------------------------ durable identity

    @Test
    void identityIsDurablePerDatabaseAcrossReopen() {
        Path database = tempDir.resolve("identity.db");
        String identity;
        try (SqliteInboxStore store = new SqliteInboxStore(database)) {
            identity = store.identity();
            assertThat(identity).isNotBlank();
            assertThat(store.identity()).as("同一库内的身份稳定").isEqualTo(identity);
        }
        try (SqliteInboxStore reopened = new SqliteInboxStore(database)) {
            assertThat(reopened.identity()).as("普通重启不改变身份").isEqualTo(identity);
        }
        try (SqliteInboxStore separate =
                     new SqliteInboxStore(tempDir.resolve("identity-other.db"))) {
            assertThat(separate.identity()).as("另一个库有不同身份").isNotEqualTo(identity);
        }
    }

    @Test
    void rotationReplacesTheIdentityAndSurvivesReopen() {
        Path database = tempDir.resolve("rotation.db");
        String rotated;
        try (SqliteInboxStore store = new SqliteInboxStore(database)) {
            String before = store.identity();
            rotated = store.rotateIdentity();
            assertThat(rotated).isNotBlank().isNotEqualTo(before);
            assertThat(store.identity()).isEqualTo(rotated);
        }
        try (SqliteInboxStore reopened = new SqliteInboxStore(database)) {
            assertThat(reopened.identity()).isEqualTo(rotated);
        }
    }

    @Test
    void rotationFailsClosedWhenTheStoreCannotWrite() {
        SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("closed.db"));
        store.close();
        assertThatThrownBy(store::rotateIdentity)
                .as("写失败必须显式报错，不能静默保留旧身份")
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ guarded ACK rejections

    @Test
    void identityMismatchRejectsBeforeChangingAnyRow() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("mismatch.db"))) {
            InboxService service = service(store);
            InboxPanel pushed = appendAndPush(service, "qq_private/1", "hello");
            String itemId = pushed.items().getFirst().id();
            long ackedBefore = service.status().ackedRevision();
            long panelBefore = service.panel().revision();

            assertThatThrownBy(() -> service.ackItemsGuarded(Set.of(itemId), pushed.revision(),
                    "another-database"))
                    .isInstanceOfSatisfying(InboxAckRejectedException.class, rejection ->
                            assertThat(rejection.reason()).isEqualTo(
                                    InboxAckRejectedException.Reason.INBOX_IDENTITY_MISMATCH));

            assertThat(store.item(itemId).orElseThrow().status())
                    .isEqualTo(ItemStatus.AVAILABLE);
            assertThat(service.status().ackedRevision()).isEqualTo(ackedBefore);
            assertThat(service.panel().revision()).isEqualTo(panelBefore);
        }
    }

    @Test
    void anIdentityRotatedDuringTheOperationRejectsTheOldOne() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("during.db"))) {
            InboxService service = service(store);
            InboxPanel pushed = appendAndPush(service, "qq_private/1", "hello");
            String itemId = pushed.items().getFirst().id();
            String oldIdentity = service.inboxIdentity();
            String newIdentity = store.rotateIdentity();
            assertThat(newIdentity).isNotEqualTo(oldIdentity);

            assertThatThrownBy(() -> service.ackItemsGuarded(Set.of(itemId), pushed.revision(),
                    oldIdentity))
                    .isInstanceOfSatisfying(InboxAckRejectedException.class, rejection ->
                            assertThat(rejection.reason()).isEqualTo(
                                    InboxAckRejectedException.Reason.INBOX_IDENTITY_MISMATCH));
            assertThat(store.item(itemId).orElseThrow().status())
                    .isEqualTo(ItemStatus.AVAILABLE);

            InboxAck accepted = service.ackItemsGuarded(Set.of(itemId), pushed.revision(),
                    newIdentity);
            assertThat(accepted.complete()).isTrue();
            assertThat(accepted.inboxIdentity()).isEqualTo(newIdentity);
            assertThat(accepted.ackedItemIds()).containsExactly(itemId);
        }
    }

    @Test
    void aMissingIdentityIsRejectedRatherThanTreatedAsLegacy() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("no-identity.db"))) {
            InboxService service = service(store);
            InboxPanel pushed = appendAndPush(service, "qq_private/1", "hello");
            String itemId = pushed.items().getFirst().id();

            assertThatThrownBy(() -> service.ackItemsGuarded(Set.of(itemId), pushed.revision(),
                    "  "))
                    .isInstanceOfSatisfying(InboxAckRejectedException.class, rejection ->
                            assertThat(rejection.reason()).isEqualTo(
                                    InboxAckRejectedException.Reason.INBOX_IDENTITY_REQUIRED));
            assertThat(service.items()).hasSize(1);
        }
    }

    @Test
    void mixedKnownAndUnknownIdsAreRejectedWithoutPartialAck() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("mixed.db"))) {
            InboxService service = service(store);
            InboxPanel pushed = appendAndPush(service, "qq_private/1", "hello");
            String itemId = pushed.items().getFirst().id();
            String identity = service.inboxIdentity();

            assertThatThrownBy(() -> service.ackItemsGuarded(
                    Set.of(itemId, "itm-unknown"), pushed.revision(), identity))
                    .isInstanceOfSatisfying(InboxAckRejectedException.class, rejection -> {
                        assertThat(rejection.reason()).isEqualTo(
                                InboxAckRejectedException.Reason.ITEM_MISSING);
                        assertThat(rejection.itemIds()).containsExactly("itm-unknown");
                    });
            assertThat(store.item(itemId).orElseThrow().status())
                    .isEqualTo(ItemStatus.AVAILABLE);
        }
    }

    @Test
    void lostResponseReAckIsSafeButAPurgedIdIsNot() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("lost.db"))) {
            InboxService service = service(store);
            InboxPanel pushed = appendAndPush(service, "qq_private/1", "hello");
            String itemId = pushed.items().getFirst().id();
            String identity = service.inboxIdentity();

            InboxAck first = service.ackItemsGuarded(Set.of(itemId), pushed.revision(), identity);
            assertThat(first.ackedItemIds()).containsExactly(itemId);
            assertThat(first.alreadyAckedItemIds()).isEmpty();
            assertThat(first.missingItemIds()).isEmpty();
            assertThat(first.complete()).isTrue();

            InboxAck replay = service.ackItemsGuarded(Set.of(itemId), pushed.revision(), identity);
            assertThat(replay.ackedItemIds()).isEmpty();
            assertThat(replay.alreadyAckedItemIds()).containsExactly(itemId);
            assertThat(replay.complete()).isTrue();

            store.purge(Instant.now().plusSeconds(60), 5000);
            assertThat(store.item(itemId)).isEmpty();

            assertThatThrownBy(() -> service.ackItemsGuarded(Set.of(itemId), pushed.revision(),
                    identity))
                    .as("已清理的 ID 不能仅凭不存在就假定已确认")
                    .isInstanceOfSatisfying(InboxAckRejectedException.class, rejection -> {
                        assertThat(rejection.reason()).isEqualTo(
                                InboxAckRejectedException.Reason.ITEM_MISSING);
                        assertThat(rejection.itemIds()).containsExactly(itemId);
                    });
        }
    }

    @Test
    void aGuardedAckNeverTreatsAMissingOrNegativeRevisionAsAbsent() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("revision.db"))) {
            InboxService service = service(store);
            InboxPanel pushed = appendAndPush(service, "qq_private/1", "hello");
            String itemId = pushed.items().getFirst().id();
            String identity = service.inboxIdentity();

            assertThatThrownBy(() -> service.ackItemsGuarded(Set.of(itemId), null, identity))
                    .as("缺失 revision 不能绕过版本校验")
                    .isInstanceOfSatisfying(InboxAckRejectedException.class, rejection ->
                            assertThat(rejection.reason()).isEqualTo(
                                    InboxAckRejectedException.Reason.REVISION_REQUIRED));
            assertThat(store.item(itemId).orElseThrow().status())
                    .isEqualTo(ItemStatus.AVAILABLE);

            assertThatThrownBy(() -> service.ackItemsGuarded(Set.of(itemId), -1L, identity))
                    .isInstanceOfSatisfying(InboxAckRejectedException.class, rejection ->
                            assertThat(rejection.reason()).isEqualTo(
                                    InboxAckRejectedException.Reason.REVISION_INVALID));
            assertThat(store.item(itemId).orElseThrow().status())
                    .isEqualTo(ItemStatus.AVAILABLE);

            assertThatThrownBy(() -> service.ackItemsGuarded(Set.of(itemId), Long.MAX_VALUE,
                    identity))
                    .as("long 范围内但过新的 revision 仍是冲突")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("REVISION_CONFLICT");
            assertThat(store.item(itemId).orElseThrow().status())
                    .isEqualTo(ItemStatus.AVAILABLE);
        }
    }

    @Test
    void anItemThatWasNeverPushedIsNotEligibleForAGuardedAck() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("unpushed.db"))) {
            InboxService service = service(store);
            append(service, "qq_private/1", "hello");
            PanelItem item = service.panel().items().getFirst();
            assertThat(item.frozenRevision()).isZero();

            assertThatThrownBy(() -> service.ackItemsGuarded(Set.of(item.id()),
                    service.panel().revision(), service.inboxIdentity()))
                    .isInstanceOfSatisfying(InboxAckRejectedException.class, rejection -> {
                        assertThat(rejection.reason()).isEqualTo(
                                InboxAckRejectedException.Reason.ITEM_NOT_PUSHED);
                        assertThat(rejection.itemIds()).containsExactly(item.id());
                    });
            assertThat(service.items()).hasSize(1);
        }
    }

    @Test
    void anEmptyIdListNeverFallsBackToRevisionOnlyAck() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("empty-ids.db"))) {
            InboxService service = service(store);
            InboxPanel pushed = appendAndPush(service, "qq_private/1", "hello");

            assertThatThrownBy(() -> service.ackItemsGuarded(Set.of(), pushed.revision(),
                    service.inboxIdentity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ITEM_IDS_EMPTY");
            assertThat(service.items()).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ atomic writes

    @Test
    void aDatabaseWriteFailureDuringAckRollsBackEveryChange() {
        Path database = tempDir.resolve("rollback.db");
        SqliteInboxStore real = new SqliteInboxStore(database);
        FaultyStore faulty = new FaultyStore(real);
        InboxService service = service(faulty);
        try {
            append(service, "qq_private/1", "first");
            append(service, "qq_private/2", "second");
            InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
            assertThat(pushed).isNotNull();
            assertThat(pushed.items()).hasSize(2);
            List<String> ids = pushed.items().stream().map(PanelItem::id).toList();
            long ackedBefore = service.status().ackedRevision();

            faulty.failOnUpdateNumber(2);
            assertThatThrownBy(() -> service.ackItemsGuarded(Set.copyOf(ids), pushed.revision(),
                    service.inboxIdentity()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("INJECTED_WRITE_FAILURE");

            assertThat(service.items()).as("内存视图也不能部分确认").hasSize(2);
            assertThat(service.status().ackedRevision()).isEqualTo(ackedBefore);
            assertThat(service.panel().revision())
                    .as("失败的 ACK 不能推进 panel revision")
                    .isEqualTo(pushed.revision());

            try (SqliteInboxStore observer = new SqliteInboxStore(database)) {
                assertThat(observer.items()).extracting(InboxItem::status)
                        .as("数据库里必须整体回滚")
                        .containsOnly(ItemStatus.AVAILABLE);
                assertThat(observer.ackedRevision()).isZero();
            }

            // A normal rollback must leave the connection usable: the same store commits the retry.
            faulty.stopFailing();
            InboxAck recovered = service.ackItemsGuarded(Set.copyOf(ids), pushed.revision(),
                    service.inboxIdentity());
            assertThat(recovered.complete()).isTrue();
            assertThat(recovered.ackedItemIds()).containsExactlyInAnyOrderElementsOf(ids);
            assertThat(service.items()).isEmpty();
        } finally {
            service.close();
            faulty.close();
        }

        try (SqliteInboxStore reopened = new SqliteInboxStore(database)) {
            assertThat(reopened.items()).extracting(InboxItem::status)
                    .as("重试成功后整体已确认")
                    .containsOnly(ItemStatus.ACKED);
            assertThat(reopened.ackedRevision()).isPositive();
        }
    }

    @Test
    void aLegacyManualAckWriteFailureAlsoRollsBack() {
        Path database = tempDir.resolve("legacy-rollback.db");
        SqliteInboxStore real = new SqliteInboxStore(database);
        FaultyStore faulty = new FaultyStore(real);
        InboxService service = service(faulty);
        try {
            append(service, "qq_private/1", "first");
            append(service, "qq_private/2", "second");
            InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
            assertThat(pushed).isNotNull();
            List<String> ids = pushed.items().stream().map(PanelItem::id).toList();
            long ackedBefore = service.status().ackedRevision();

            faulty.failOnUpdateNumber(2);
            assertThatThrownBy(() -> service.ackItems(Set.copyOf(ids), pushed.revision()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("INJECTED_WRITE_FAILURE");

            assertThat(service.items()).hasSize(2);
            assertThat(service.status().ackedRevision()).isEqualTo(ackedBefore);
        } finally {
            service.close();
            faulty.close();
        }
        try (SqliteInboxStore reopened = new SqliteInboxStore(database)) {
            assertThat(reopened.items()).extracting(InboxItem::status)
                    .containsOnly(ItemStatus.AVAILABLE);
        }
    }

    // ------------------------------------------------------------------ legacy manual path

    @Test
    void theLegacyManualAckStillWorksAndReportsProof() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("legacy.db"))) {
            InboxService service = service(store);
            InboxPanel pushed = appendAndPush(service, "qq_private/1", "hello");
            String itemId = pushed.items().getFirst().id();

            InboxAck mixed = service.ackItems(Set.of(itemId, "itm-not-there"),
                    pushed.revision());
            assertThat(mixed.ackedItemIds()).containsExactly(itemId);
            assertThat(mixed.missingItemIds()).containsExactly("itm-not-there");
            assertThat(mixed.complete())
                    .as("既有宽容路径仍成功，但必须如实报告缺失项")
                    .isFalse();
            assertThat(store.item(itemId).orElseThrow().status()).isEqualTo(ItemStatus.ACKED);
        }
    }

    @Test
    void theManualAckToolPathCanStillAckAnUnpushedItem() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("manual.db"))) {
            InboxService service = service(store);
            append(service, "qq_private/1", "hello");
            PanelItem item = service.panel().items().getFirst();

            InboxAck ack = service.ackItems(Set.of(item.id()), service.panel().revision());
            assertThat(ack.complete()).isTrue();
            assertThat(ack.ackedItemIds()).containsExactly(item.id());
            assertThat(service.items()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ fault injection

    /**
     * Delegating store that can fail a chosen {@code update} call. Its
     * {@link #inTransaction(Runnable)} delegates to the real store, so the rollback under test is
     * SQLite's own, not a test double's.
     */
    private static final class FaultyStore implements InboxStore {

        private final InboxStore delegate;
        private int updatesUntilFailure = -1;

        private FaultyStore(InboxStore delegate) {
            this.delegate = delegate;
        }

        /** Fails the 1-based {@code number}-th {@code update} call (1 = the first). */
        private void failOnUpdateNumber(int number) {
            this.updatesUntilFailure = Math.max(0, number - 1);
        }

        /** Stops failing, so the same connection can prove it is still usable after a rollback. */
        private void stopFailing() {
            this.updatesUntilFailure = -1;
        }

        @Override
        public void update(InboxItem item) {
            if (updatesUntilFailure == 0) {
                throw new IllegalStateException("INJECTED_WRITE_FAILURE");
            }
            if (updatesUntilFailure > 0) {
                updatesUntilFailure--;
            }
            delegate.update(item);
        }

        @Override
        public void append(InboxItem item) {
            delegate.append(item);
        }

        @Override
        public void freeze(List<String> itemIds, long pushRevision) {
            delegate.freeze(itemIds, pushRevision);
        }

        @Override
        public Optional<InboxItem> item(String id) {
            return delegate.item(id);
        }

        @Override
        public Optional<InboxItem> activeForSource(String source) {
            return delegate.activeForSource(source);
        }

        @Override
        public boolean hasUnackedFrozenForSource(String source) {
            return delegate.hasUnackedFrozenForSource(source);
        }

        @Override
        public void delete(List<String> itemIds) {
            delegate.delete(itemIds);
        }

        @Override
        public List<InboxItem> items() {
            return delegate.items();
        }

        @Override
        public int purge(Instant cutoff, int maxItems) {
            return delegate.purge(cutoff, maxItems);
        }

        @Override
        public String identity() {
            return delegate.identity();
        }

        @Override
        public String rotateIdentity() {
            return delegate.rotateIdentity();
        }

        @Override
        public void inTransaction(Runnable work) {
            delegate.inTransaction(work);
        }

        @Override
        public long panelRevision() {
            return delegate.panelRevision();
        }

        @Override
        public void savePanelRevision(long revision) {
            delegate.savePanelRevision(revision);
        }

        @Override
        public long ackedRevision() {
            return delegate.ackedRevision();
        }

        @Override
        public void saveAckedRevision(long revision) {
            delegate.saveAckedRevision(revision);
        }

        @Override
        public long pushRevision() {
            return delegate.pushRevision();
        }

        @Override
        public void savePushRevision(long revision) {
            delegate.savePushRevision(revision);
        }

        @Override
        public String state(String key) {
            return delegate.state(key);
        }

        @Override
        public void setState(String key, String value) {
            delegate.setState(key, value);
        }

        @Override
        public boolean ping() {
            return delegate.ping();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
