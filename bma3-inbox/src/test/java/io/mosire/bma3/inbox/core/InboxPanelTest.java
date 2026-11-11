package io.mosire.bma3.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.core.source.SourceView;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboxPanelTest {

    @TempDir
    Path tempDir;

    private static InboxService service(SqliteInboxStore store, int maxMessages, int maxItems) {
        return new InboxService(store,
                new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30),
                        Duration.ofMinutes(10), 3),
                "bma3", () -> new InboxRuntime(maxMessages, maxItems, true));
    }

    @Test
    void keepsOnlyTheMostRecentWindowButCountsEverything() {
        Instant t0 = Instant.parse("2026-11-10T00:00:00Z");
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("inbox.db"))) {
            InboxService service = service(store, 6, 6);
            SourceView source = SourceView.of("qq_group/bot-1/955432692", "QQ群 955432692");
            for (int i = 1; i <= 50; i++) {
                service.appendOrMerge(AppendItem.of(source,
                        new InboxEntry(i, t0.plusSeconds(i), "LvtN", "line-" + i,
                                i == 50 ? List.of("[@3924945183]", "[引用 #49 Bob]", "[图片]")
                                        : List.of()),
                        300, t0.plusSeconds(i)));
            }
            InboxItem item = store.activeForSource(source.raw()).orElseThrow();
            assertThat(item.entries()).hasSize(6);
            assertThat(item.totalMessages()).isEqualTo(50);
            assertThat(item.summary().lines()).hasSize(6);
            assertThat(item.summary()).doesNotContain("line-1\n");

            InboxPanel panel = service.panel();
            assertThat(panel.items()).hasSize(1);
            assertThat(panel.items().getFirst().sourceLabel()).isEqualTo("QQ群 955432692");
            assertThat(panel.items().getFirst().omittedMessages()).isEqualTo(44);
            String lastLine = panel.items().getFirst().entries().getLast().line();
            assertThat(lastLine).contains("LvtN").contains("[@3924945183]")
                    .contains("[引用 #49 Bob]").contains("[图片]");
            assertThat(lastLine).doesNotContain("multimedia.nt.qq.com.cn");
        }
    }

    @Test
    void limitsPanelItemsAndSortsByPriority() {
        Instant t0 = Instant.parse("2026-11-10T00:00:00Z");
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("inbox.db"))) {
            InboxService service = service(store, 6, 4);
            for (int i = 0; i < 8; i++) {
                SourceView source = SourceView.of("bilibili/" + (100 + i), "B站 " + (100 + i));
                service.appendOrMerge(AppendItem.of(source,
                        new InboxEntry(i, t0.plusSeconds(i), "user", "post-" + i,
                                List.of("[图片]")),
                        100 + i * 10, t0.plusSeconds(i)));
            }
            InboxPanel panel = service.panel();
            assertThat(panel.items()).hasSize(4);
            assertThat(panel.totalItems()).isEqualTo(8);
            assertThat(panel.omittedItems()).isEqualTo(4);
            assertThat(panel.items()).extracting(PanelItem::priority)
                    .containsExactly(170, 160, 150, 140);
            assertThat(panel.items().getFirst().sourceLabel()).startsWith("B站 ");
        }
    }

    @Test
    void ackingAnOlderRevisionDoesNotConsumeANewerItem() {
        Instant t0 = Instant.parse("2026-11-10T00:00:00Z");
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("target-ack.db"))) {
            InboxService service = service(store, 6, 6);
            SourceView first = SourceView.of("qq_group/bot-1/1", "QQ群 1");
            service.appendOrMerge(AppendItem.of(first,
                    new InboxEntry(1, t0, "u", "first", List.of()), 300, t0));
            long seen = service.panel().revision();

            SourceView second = SourceView.of("qq_group/bot-1/2", "QQ群 2");
            service.appendOrMerge(AppendItem.of(second,
                    new InboxEntry(2, t0.plusSeconds(1), "u", "second", List.of()),
                    300, t0.plusSeconds(1)));

            InboxAck ack = service.ackPanel(seen);
            assertThat(ack.ackedRevision()).isEqualTo(seen);
            assertThat(service.panel().items()).hasSize(1);
            assertThat(service.panel().items().getFirst().source()).isEqualTo(second.raw());
        }
    }

    @Test
    void windowSizeIsReadFromTheLivePolicy() {
        Instant t0 = Instant.parse("2026-11-10T00:00:00Z");
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("hot.db"))) {
            java.util.concurrent.atomic.AtomicInteger maxMessages =
                    new java.util.concurrent.atomic.AtomicInteger(6);
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30),
                            Duration.ofMinutes(10), 3),
                    "bma3", () -> new InboxRuntime(maxMessages.get(), 6, true));
            SourceView source = SourceView.of("qq_group/bot-1/1", "QQ群 1");
            for (int i = 1; i <= 6; i++) {
                service.appendOrMerge(AppendItem.of(source,
                        new InboxEntry(i, t0.plusSeconds(i), "u", "m" + i, List.of()),
                        300, t0.plusSeconds(i)));
            }
            assertThat(store.activeForSource(source.raw()).orElseThrow().entries()).hasSize(6);

            maxMessages.set(4);
            service.appendOrMerge(AppendItem.of(source,
                    new InboxEntry(7, t0.plusSeconds(7), "u", "m7", List.of()),
                    300, t0.plusSeconds(7)));
            assertThat(store.activeForSource(source.raw()).orElseThrow().entries()).hasSize(4);
        }
    }

    @Test
    void ackAdvancesThePanelCursorWithoutBlockingNewItems() {
        Instant t0 = Instant.parse("2026-11-10T00:00:00Z");
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("inbox.db"))) {
            InboxService service = service(store, 6, 6);
            SourceView source = SourceView.of("qq_private/bot-1/123", "QQ私聊 123");
            service.appendOrMerge(AppendItem.of(source,
                    new InboxEntry(1, t0, "Alice", "hello", List.of()), 300, t0));
            assertThat(service.panel().items()).hasSize(1);

            InboxAck ack = service.ackPanel();
            assertThat(ack.ackedRevision()).isPositive();
            assertThat(service.panel().items()).isEmpty();

            service.appendOrMerge(AppendItem.of(source,
                    new InboxEntry(2, t0.plusSeconds(1), "Alice", "again", List.of()),
                    300, t0.plusSeconds(1)));
            assertThat(service.panel().items()).hasSize(1);
            assertThat(service.panel().items().getFirst().entries().getFirst().text())
                    .isEqualTo("again");
        }
    }
}
