package io.github.c1589190.bma3.app.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.bma3.app.channel.router.QqChannelRouteInput;
import io.github.c1589190.bma3.app.channel.router.QqChannelRouteResult;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore;
import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.core.message.Direction;
import io.github.c1589190.bma3.core.message.InboundMessage;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.source.SourceView;
import io.github.c1589190.bma3.core.store.SqliteMessageBoardStore;
import io.github.c1589190.bma3.inbox.core.DeliveryPolicy;
import io.github.c1589190.bma3.inbox.core.InboxEntry;
import io.github.c1589190.bma3.inbox.core.InboxService;
import io.github.c1589190.bma3.inbox.core.SqliteInboxStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqChannelRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String RAW = "qq_group/bot-1/955432692";

    @TempDir Path root;

    @Test
    void recoveryPrecedesToolAndIngressAccessAndPersistsTheCoordinatedBinding() {
        var settings = new AtomicReference<>(new AppConfig.QqOfficial.Matching("strict", 5));
        try (var board = new SqliteMessageBoardStore(root.resolve("bot.db"));
             var inboxStore = new SqliteInboxStore(root.resolve("inbox.db"))) {
            InboxService inbox = new InboxService(inboxStore,
                    new DeliveryPolicy(0, Duration.ofMillis(1), Duration.ofMillis(1)));
            try (var runtime = runtime(board, inboxStore, inbox, settings)) {
                assertThatThrownBy(runtime::tools).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(runtime::router).isInstanceOf(IllegalStateException.class);
                assertThat(runtime.recoverBeforeServing()).isNotNull();
                assertThat(runtime.tools()).extracting(tool -> tool.name()).contains(
                        "qq_thread_bind", "qq_thread_unbind", "qq_thread_compare",
                        "qq_thread_history");
                runtime.coordinator().bind("logical-1", RAW, "群名称");
                assertThat(runtime.observations().sourceBinding(RAW)).get()
                        .satisfies(binding -> {
                            assertThat(binding.active()).isTrue();
                            assertThat(binding.startBoardSeq()).isEqualTo(1L);
                        });

                QqChannelRouteResult routed = runtime.router().ingest(input("message-1"));
                assertThat(routed.disposition())
                        .isEqualTo(QqChannelRouteResult.Disposition.BOUND_DISPLAY);
                assertThat(inboxStore.items()).singleElement().satisfies(item -> {
                    assertThat(item.source()).isEqualTo("qq_thread/logical-1");
                    assertThat(item.sourceLabel()).isEqualTo("群名称");
                    assertThat(item.entries()).singleElement().satisfies(entry ->
                            assertThat(entry.localSeq()).isEqualTo(board.findInbound(
                                    SourceAddress.parse(RAW), "message-1")
                                    .orElseThrow().localSeq()));
                });
                assertThat(runtime.ping()).isTrue();
            }
            try (var restarted = runtime(board, inboxStore, inbox, settings)) {
                restarted.recoverBeforeServing();
                assertThat(restarted.registry().binding(RAW)).get()
                        .satisfies(binding -> assertThat(binding.active()).isTrue());
                assertThat(restarted.observations().sourceBinding(RAW)).get()
                        .satisfies(binding -> assertThat(binding.active()).isTrue());
                assertThat(inboxStore.items()).hasSize(1);
            }
        }
    }

    @Test
    void changedDefaultsOnlyAffectNewBindings() {
        var settings = new AtomicReference<>(new AppConfig.QqOfficial.Matching("strict", 5));
        try (var board = new SqliteMessageBoardStore(root.resolve("bot.db"));
             var inboxStore = new SqliteInboxStore(root.resolve("inbox.db"))) {
            InboxService inbox = new InboxService(inboxStore,
                    new DeliveryPolicy(0, Duration.ofMillis(1), Duration.ofMillis(1)));
            try (var runtime = runtime(board, inboxStore, inbox, settings)) {
                runtime.recoverBeforeServing();
                runtime.coordinator().bind("old", RAW, "");
                settings.set(new AppConfig.QqOfficial.Matching("off", 7));
                runtime.coordinator().bind("new", "qq_private/bot-1/12345", "");
                assertThat(runtime.registry().status("old")).get()
                        .satisfies(status -> assertThat(status.thread().matchingMode())
                                .isEqualTo(QqChannelRegistry.MatchingMode.STRICT));
                assertThat(runtime.registry().status("new")).get()
                        .satisfies(status -> assertThat(status.thread().matchingMode())
                                .isEqualTo(QqChannelRegistry.MatchingMode.OFF));
                runtime.router().ingest(input("after-reload"));
                assertThat(runtime.observations().findIngestIntentByMessage(RAW, "after-reload"))
                        .get().satisfies(intent ->
                                assertThat(intent.strictTimeToleranceSeconds()).isEqualTo(7));
            }
        }
    }

    private QqChannelRuntime runtime(SqliteMessageBoardStore board, SqliteInboxStore store,
                                     InboxService inbox,
                                     AtomicReference<AppConfig.QqOfficial.Matching> settings) {
        return new QqChannelRuntime(root, board, inbox, store,
                (channel, account) -> channel == QqChannelRegistry.Channel.ONEBOT
                        && "bot-1".equals(account),
                (channel, account) -> true, settings::get, CLOCK);
    }

    private static QqChannelRouteInput input(String id) {
        SourceAddress source = SourceAddress.parse(RAW);
        List<Segment> segments = List.of(new Segment("text", "hello"));
        InboundMessage boardMessage = new InboundMessage(source, id, NOW, segments,
                Direction.INBOUND, "10001", "Sender");
        InboxEntry preview = new InboxEntry(0L, NOW, "Sender", "hello", List.of());
        return new QqChannelRouteInput(false, "bot-1", source, id, "10001",
                Direction.INBOUND, NOW, NOW, 200,
                QqChannelObservationStore.TimeReliability.RELIABLE,
                QqChannelObservationStore.ContentIntegrity.COMPLETE,
                false, segments, boardMessage, preview,
                QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE,
                SourceView.of(source, "原始群名称"));
    }
}
