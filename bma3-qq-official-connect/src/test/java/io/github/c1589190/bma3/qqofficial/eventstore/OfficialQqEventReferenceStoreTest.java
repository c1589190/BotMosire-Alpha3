package io.github.c1589190.bma3.qqofficial.eventstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.bma3.core.message.Direction;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqEvent;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqSystemEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialQqEventReferenceStoreTest {

    private static final Instant T0 = Instant.parse("2026-11-17T00:00:00Z");
    private static final Clock FIXED = Clock.fixed(T0, ZoneOffset.UTC);
    private static final String GROUP_SOURCE = "qq_official_group/bot-a/group-openid-a";
    private static final String PRIVATE_SOURCE = "qq_official_private/bot-a/user-openid-a";

    @TempDir
    Path tempDir;

    @Test
    void recordsMessageAndSystemReferencesAndSurvivesRestart() {
        Path file = tempDir.resolve("event-refs.db");
        OfficialQqEvent message = messageEvent(
                "event-message", "message-a", "group-openid-a", "BODY-SECRET auth_token=x");
        OfficialQqSystemEvent groupSystem = systemEvent(
                "event-system-group", "GROUP_ADD_ROBOT", "group-openid-a", "");
        OfficialQqSystemEvent friendSystem = systemEvent(
                "event-system-friend", "FRIEND_ADD", "", "user-openid-a");

        try (OfficialQqEventReferenceStore store = new OfficialQqEventReferenceStore(file, FIXED)) {
            var messageResult = store.recordMessage("bot-a", message);
            assertThat(messageResult.created()).isTrue();
            assertThat(messageResult.reference().accountId()).isEqualTo("bot-a");
            assertThat(messageResult.reference().eventId()).isEqualTo("event-message");
            assertThat(messageResult.reference().source()).isEqualTo(GROUP_SOURCE);
            assertThat(messageResult.reference().messageId()).isEqualTo("message-a");
            assertThat(messageResult.reference().eventType()).isEqualTo("GROUP_AT_MESSAGE_CREATE");
            assertThat(messageResult.reference().direction()).isEqualTo(Direction.INBOUND);
            assertThat(messageResult.reference().replyable()).isFalse();
            assertThat(messageResult.reference().observedAt()).isEqualTo(T0);

            assertThat(store.recordSystem("bot-a", groupSystem).reference().source())
                    .isEqualTo(GROUP_SOURCE);
            assertThat(store.recordSystem("bot-a", friendSystem).reference().source())
                    .isEqualTo(PRIVATE_SOURCE);
            assertThat(store.recordSystem("bot-a", friendSystem).created()).isFalse();
            assertThat(store.count()).isEqualTo(3);
            assertThat(store.ping()).isTrue();
        }

        try (OfficialQqEventReferenceStore reopened =
                     new OfficialQqEventReferenceStore(file, FIXED)) {
            assertThat(reopened.count()).isEqualTo(3);
            OfficialQqEventReferenceStore.Reference group = reopened.requireReplyable(
                    "bot-a", SourceAddress.parse(GROUP_SOURCE), "event-system-group");
            assertThat(group.messageId()).isEmpty();
            assertThat(group.eventType()).isEqualTo("GROUP_ADD_ROBOT");
            assertThat(reopened.requireReplyable(
                    "bot-a", SourceAddress.parse(PRIVATE_SOURCE), "event-system-friend")
                    .eventType()).isEqualTo("FRIEND_ADD");
            OfficialQqEventReferenceStore.Reference messageReference = reopened.find(
                    "bot-a", SourceAddress.parse(GROUP_SOURCE), "event-message").orElseThrow();
            assertThat(messageReference.messageId()).isEqualTo("message-a");
            assertThat(messageReference.replyable()).isFalse();
            assertThatThrownBy(() -> reopened.requireReplyable(
                    "bot-a", SourceAddress.parse(GROUP_SOURCE), "event-message"))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.NOT_REPLYABLE));
        }
    }

    @Test
    void duplicateIsIdempotentButConflictingReuseFailsClosed() {
        try (OfficialQqEventReferenceStore store = new OfficialQqEventReferenceStore(
                tempDir.resolve("duplicates.db"), FIXED)) {
            OfficialQqEvent event = messageEvent(
                    "same-event", "message-1", "group-openid-a", "body");
            var first = store.recordMessage("bot-a", event);
            var replay = store.recordMessage("bot-a", event);
            assertThat(first.created()).isTrue();
            assertThat(replay.created()).isFalse();
            assertThat(replay.reference()).isEqualTo(first.reference());
            assertThat(store.count()).isEqualTo(1);

            OfficialQqEvent differentGroup = messageEvent(
                    "same-event", "message-1", "group-openid-b", "body");
            assertThatThrownBy(() -> store.recordMessage("bot-a", differentGroup))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code
                                    .EVENT_REFERENCE_CONFLICT));

            OfficialQqEvent differentMessage = messageEvent(
                    "same-event", "message-2", "group-openid-a", "body");
            assertThatThrownBy(() -> store.recordMessage("bot-a", differentMessage))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code
                                    .EVENT_REFERENCE_CONFLICT));
            assertThat(store.count()).isEqualTo(1);
        }
    }

    @Test
    void exactLookupRejectsCrossAccountCrossSourceUnknownAndOutbound() {
        try (OfficialQqEventReferenceStore store = new OfficialQqEventReferenceStore(
                tempDir.resolve("rejections.db"), FIXED)) {
            store.recordSystem("bot-a", systemEvent(
                    "event-a", "GROUP_ADD_ROBOT", "group-openid-a", ""));

            assertThat(store.requireReplyable(
                    "bot-a", SourceAddress.parse(GROUP_SOURCE), "event-a").eventId())
                    .isEqualTo("event-a");

            assertThatThrownBy(() -> store.requireReplyable(
                    "bot-b", SourceAddress.parse("qq_official_group/bot-b/group-openid-a"),
                    "event-a"))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.ACCOUNT_MISMATCH));

            assertThatThrownBy(() -> store.requireReplyable(
                    "bot-a", SourceAddress.parse("qq_official_group/bot-a/group-openid-b"),
                    "event-a"))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.SOURCE_MISMATCH));

            assertThatThrownBy(() -> store.requireReplyable(
                    "bot-a", SourceAddress.parse(GROUP_SOURCE), "event-unknown"))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code
                                    .UNKNOWN_EVENT_REFERENCE));

            assertThatThrownBy(() -> store.record(
                    "bot-a", "event-outbound", "GROUP_AT_MESSAGE_CREATE",
                    SourceAddress.parse(GROUP_SOURCE), "message-outbound",
                    Direction.OUTBOUND, T0))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.NOT_INBOUND));
            assertThat(store.count()).isEqualTo(1);
        }
    }

    @Test
    void onlyConcreteSourceBearingSystemEventsBecomeReplyable() {
        try (OfficialQqEventReferenceStore store = new OfficialQqEventReferenceStore(
                tempDir.resolve("system-events.db"), FIXED)) {
            assertThatThrownBy(() -> store.recordSystem(
                    "bot-a", systemEvent("event-ready", "READY", "", "")))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.NOT_REPLYABLE));

            assertThatThrownBy(() -> store.recordSystem(
                    "bot-a", systemEvent("event-no-group", "GROUP_ADD_ROBOT", "", "")))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.INVALID_SOURCE));

            assertThatThrownBy(() -> store.recordSystem(
                    "bot-a", systemEvent("event-no-friend", "FRIEND_ADD", "", "")))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.INVALID_SOURCE));
            assertThat(store.count()).isZero();
            assertThat(store.find(
                    "bot-a", SourceAddress.parse(GROUP_SOURCE), "event-ready")).isEmpty();
        }
    }

    @Test
    void eventIdReplyWhitelistIsScenarioAndTypeStrict() throws Exception {
        Path file = tempDir.resolve("reply-whitelist.db");
        try (OfficialQqEventReferenceStore store = new OfficialQqEventReferenceStore(file, FIXED)) {
            // Positive: the three reliably parsed scenario/event-type pairs.
            OfficialQqEventReferenceStore.Reference groupAdd = store.recordSystem(
                    "bot-a", systemEvent(
                            "event-group-add", "GROUP_ADD_ROBOT", "group-openid-a", ""))
                    .reference();
            OfficialQqEventReferenceStore.Reference groupReceive = store.recordSystem(
                    "bot-a", systemEvent(
                            "event-group-receive", "GROUP_MSG_RECEIVE", "group-openid-a", ""))
                    .reference();
            OfficialQqEventReferenceStore.Reference friendAdd = store.recordSystem(
                    "bot-a", systemEvent(
                            "event-friend-add", "FRIEND_ADD", "", "user-openid-a"))
                    .reference();
            assertThat(groupAdd.replyable()).isTrue();
            assertThat(groupReceive.replyable()).isTrue();
            assertThat(friendAdd.replyable()).isTrue();
            assertThat(store.requireReplyable(
                    "bot-a", SourceAddress.parse(GROUP_SOURCE), "event-group-add")
                    .eventType()).isEqualTo("GROUP_ADD_ROBOT");
            assertThat(store.requireReplyable(
                    "bot-a", SourceAddress.parse(GROUP_SOURCE), "event-group-receive")
                    .eventType()).isEqualTo("GROUP_MSG_RECEIVE");
            assertThat(store.requireReplyable(
                    "bot-a", SourceAddress.parse(PRIVATE_SOURCE), "event-friend-add")
                    .eventType()).isEqualTo("FRIEND_ADD");

            // Message envelope ids are audit-only; inbound messages are replied to by msg_id.
            OfficialQqEventReferenceStore.Reference groupMessage = store.recordMessage(
                    "bot-a", messageEvent(
                            "event-group-message", "message-a", "group-openid-a", "body"))
                    .reference();
            OfficialQqEventReferenceStore.Reference c2cMessage = store.record(
                    "bot-a", "event-c2c-message", "C2C_MESSAGE_CREATE",
                    SourceAddress.parse(PRIVATE_SOURCE), "message-c2c",
                    Direction.INBOUND, T0).reference();
            assertThat(groupMessage.replyable()).isFalse();
            assertThat(c2cMessage.replyable()).isFalse();
            assertNotReplyable(store, "bot-a", GROUP_SOURCE, "event-group-message");
            assertNotReplyable(store, "bot-a", PRIVATE_SOURCE, "event-c2c-message");

            // Source-bearing but non-whitelisted system events remain durable audit facts.
            OfficialQqEventReferenceStore.Reference groupDel = store.recordSystem(
                    "bot-a", systemEvent(
                            "event-group-del", "GROUP_DEL_ROBOT", "group-openid-a", ""))
                    .reference();
            OfficialQqEventReferenceStore.Reference groupReject = store.recordSystem(
                    "bot-a", systemEvent(
                            "event-group-reject", "GROUP_MSG_REJECT", "group-openid-a", ""))
                    .reference();
            OfficialQqEventReferenceStore.Reference friendDel = store.recordSystem(
                    "bot-a", systemEvent(
                            "event-friend-del", "FRIEND_DEL", "", "user-openid-a"))
                    .reference();
            assertThat(groupDel.replyable()).isFalse();
            assertThat(groupReject.replyable()).isFalse();
            assertThat(friendDel.replyable()).isFalse();
            assertNotReplyable(store, "bot-a", GROUP_SOURCE, "event-group-del");
            assertNotReplyable(store, "bot-a", GROUP_SOURCE, "event-group-reject");
            assertNotReplyable(store, "bot-a", PRIVATE_SOURCE, "event-friend-del");

            // Simulate a corrupt/manual audit row: allowed type but wrong scenario. replyability is
            // still computed from both fields and must fail closed.
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                 PreparedStatement insert = connection.prepareStatement("""
                         INSERT INTO official_event_reference(
                           account_id, event_id, source, message_id, event_type,
                           direction, observed_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?)
                         """)) {
                insert.setString(1, "bot-a");
                insert.setString(2, "event-cross-scenario");
                insert.setString(3, PRIVATE_SOURCE);
                insert.setString(4, "");
                insert.setString(5, "GROUP_ADD_ROBOT");
                insert.setString(6, Direction.INBOUND.name());
                insert.setString(7, T0.toString());
                insert.executeUpdate();
            }
            OfficialQqEventReferenceStore.Reference crossScenario = store.find(
                    "bot-a", SourceAddress.parse(PRIVATE_SOURCE),
                    "event-cross-scenario").orElseThrow();
            assertThat(crossScenario.replyable()).isFalse();
            assertNotReplyable(store, "bot-a", PRIVATE_SOURCE, "event-cross-scenario");
        }
    }

    private static void assertNotReplyable(OfficialQqEventReferenceStore store,
                                           String accountId, String source, String eventId) {
        assertThatThrownBy(() -> store.requireReplyable(
                accountId, SourceAddress.parse(source), eventId))
                .isInstanceOf(OfficialQqEventReferenceException.class)
                .satisfies(failure -> assertThat(
                        ((OfficialQqEventReferenceException) failure).code())
                        .isEqualTo(OfficialQqEventReferenceException.Code.NOT_REPLYABLE));
    }

    @Test
    void strictValidationRejectsUnsafeIdsAndSourceMismatches() {
        try (OfficialQqEventReferenceStore store = new OfficialQqEventReferenceStore(
                tempDir.resolve("validation.db"), FIXED)) {
            assertThatThrownBy(() -> store.recordMessage("bad account", messageEvent(
                    "event-a", "message-a", "group-openid-a", "body")))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.INVALID_ACCOUNT));

            assertThatThrownBy(() -> store.record(
                    "bot-a", "bad event id", "GROUP_AT_MESSAGE_CREATE",
                    SourceAddress.parse(GROUP_SOURCE), "message-a",
                    Direction.INBOUND, T0))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.INVALID_EVENT_ID));

            assertThatThrownBy(() -> store.record(
                    "bot-a", "event-a", "GROUP_AT_MESSAGE_CREATE",
                    SourceAddress.parse(GROUP_SOURCE), "",
                    Direction.INBOUND, T0))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.INVALID_MESSAGE_ID));

            assertThatThrownBy(() -> store.record(
                    "bot-a", "event-a", "GROUP_AT_MESSAGE_CREATE",
                    SourceAddress.parse("qq_group/bot-a/123"), "message-a",
                    Direction.INBOUND, T0))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.INVALID_SOURCE));

            assertThatThrownBy(() -> store.record(
                    "bot-a", "event-a", "GROUP_AT_MESSAGE_CREATE",
                    SourceAddress.parse("qq_official_group/bot-b/group-openid-a"), "message-a",
                    Direction.INBOUND, T0))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code
                                    .ACCOUNT_SOURCE_MISMATCH));

            assertThatThrownBy(() -> store.record(
                    "bot-a", "event-a", "FUTURE_EVENT",
                    SourceAddress.parse(GROUP_SOURCE), "message-a",
                    Direction.INBOUND, T0))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.UNSUPPORTED_EVENT));

            assertThatThrownBy(() -> store.record(
                    "bot-a", "event-a", "GROUP_ADD_ROBOT",
                    SourceAddress.parse(GROUP_SOURCE), "unexpected-message-id",
                    Direction.INBOUND, T0))
                    .isInstanceOf(OfficialQqEventReferenceException.class)
                    .satisfies(failure -> assertThat(
                            ((OfficialQqEventReferenceException) failure).code())
                            .isEqualTo(OfficialQqEventReferenceException.Code.INVALID_MESSAGE_ID));
            assertThat(store.count()).isZero();
        }
    }

    @Test
    void sameEventIdIsIsolatedAcrossAccounts() {
        try (OfficialQqEventReferenceStore store = new OfficialQqEventReferenceStore(
                tempDir.resolve("accounts.db"), FIXED)) {
            store.recordSystem("bot-a", systemEvent(
                    "same-event", "GROUP_ADD_ROBOT", "group-openid-a", ""));
            store.recordSystem("bot-b", systemEvent(
                    "same-event", "GROUP_ADD_ROBOT", "group-openid-b", ""));

            assertThat(store.count()).isEqualTo(2);
            assertThat(store.requireReplyable(
                    "bot-a", SourceAddress.parse("qq_official_group/bot-a/group-openid-a"),
                    "same-event").eventType()).isEqualTo("GROUP_ADD_ROBOT");
            assertThat(store.requireReplyable(
                    "bot-b", SourceAddress.parse("qq_official_group/bot-b/group-openid-b"),
                    "same-event").eventType()).isEqualTo("GROUP_ADD_ROBOT");
        }
    }

    @Test
    void concurrentDuplicateRecordsProduceExactlyOneFact() throws Exception {
        Path file = tempDir.resolve("concurrent.db");
        try (OfficialQqEventReferenceStore store = new OfficialQqEventReferenceStore(file, FIXED)) {
            OfficialQqEvent event = messageEvent(
                    "event-concurrent", "message-concurrent", "group-openid-a", "body");
            int workers = 16;
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                tasks.add(() -> {
                    start.await();
                    return store.recordMessage("bot-a", event).created();
                });
            }
            try {
                List<Future<Boolean>> futures = new ArrayList<>();
                for (Callable<Boolean> task : tasks) {
                    futures.add(pool.submit(task));
                }
                start.countDown();
                int created = 0;
                for (Future<Boolean> future : futures) {
                    if (future.get()) {
                        created++;
                    }
                }
                assertThat(created).isEqualTo(1);
                assertThat(store.count()).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void schemaStoresOnlyRoutingFactsAndNoBodyOrCredentials() throws Exception {
        Path file = tempDir.resolve("schema.db");
        String body = "BODY-MUST-NOT-BE-STORED";
        String authToken = "AUTH-TOKEN-MUST-NOT-BE-STORED";
        try (OfficialQqEventReferenceStore store = new OfficialQqEventReferenceStore(file, FIXED)) {
            OfficialQqEvent event = messageEvent(
                    "event-schema", "message-schema", "group-openid-a",
                    body + " auth_token=" + authToken);
            store.recordMessage("bot-a", event);
            OfficialQqEventReferenceStore.Reference audit = store.find(
                    "bot-a", SourceAddress.parse(GROUP_SOURCE), "event-schema").orElseThrow();
            assertThat(audit.replyable()).isFalse();
            assertThat(audit.toString()).doesNotContain(body).doesNotContain(authToken);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery(
                     "PRAGMA table_info(official_event_reference)")) {
            List<String> names = new ArrayList<>();
            while (columns.next()) {
                names.add(columns.getString("name"));
            }
            assertThat(names).containsExactly(
                    "account_id", "event_id", "source", "message_id",
                    "event_type", "direction", "observed_at");
        }
        List<byte[]> files = new ArrayList<>();
        files.add(Files.readAllBytes(file));
        Path wal = Path.of(file + "-wal");
        if (Files.exists(wal)) {
            files.add(Files.readAllBytes(wal));
        }
        String persisted = new String(concat(files), StandardCharsets.ISO_8859_1);
        assertThat(persisted).doesNotContain(body).doesNotContain(authToken);
    }

    private static OfficialQqEvent messageEvent(String eventId, String messageId,
                                                 String groupOpenid, String text) {
        return messageEventFor("bot-a", eventId, messageId, groupOpenid, text);
    }

    private static OfficialQqEvent messageEventFor(String accountId, String eventId,
                                                    String messageId, String groupOpenid) {
        return messageEventFor(accountId, eventId, messageId, groupOpenid, "body");
    }

    private static OfficialQqEvent messageEventFor(String accountId, String eventId,
                                                    String messageId, String groupOpenid,
                                                    String text) {
        return new OfficialQqEvent(
                eventId,
                "GROUP_AT_MESSAGE_CREATE",
                messageId,
                groupOpenid,
                "user-openid-a",
                "member-openid-a",
                "Alice",
                "昵称",
                "member",
                T0,
                List.of(new Segment("text", text)),
                "",
                "trace-id",
                "",
                true,
                false,
                false,
                1L,
                T0);
    }

    private static OfficialQqSystemEvent systemEvent(String eventId, String eventType,
                                                      String groupOpenid, String userOpenid) {
        return new OfficialQqSystemEvent(
                eventId, eventType, 1L, T0, T0, false,
                groupOpenid, userOpenid, "", "", "Alice", "昵称", "", "");
    }

    private static byte[] concat(List<byte[]> values) {
        int length = values.stream().mapToInt(value -> value.length).sum();
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, out, offset, value.length);
            offset += value.length;
        }
        return out;
    }
}
