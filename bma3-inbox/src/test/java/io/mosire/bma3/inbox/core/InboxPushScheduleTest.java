package io.mosire.bma3.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.bma3.core.source.SourceView;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Push scheduling: immediate items flush after the short delay, everything else waits for the
 * batch delay, the batch is anchored on the earliest pending item (a later arrival never restarts
 * the clock), and one immediate item flushes the whole pending set with it.
 */
class InboxPushScheduleTest {

    private static final int IMMEDIATE_THRESHOLD = 300;
    private static final Duration IMPORTANT = Duration.ofMillis(80);
    private static final Duration NORMAL = Duration.ofMillis(400);

    @TempDir
    Path tempDir;

    private InboxService service() {
        DeliveryPolicy policy = new DeliveryPolicy(IMMEDIATE_THRESHOLD, IMPORTANT, NORMAL);
        SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("inbox.db"));
        return new InboxService(store, policy, "bma3", () -> new InboxRuntime(6, 6, true));
    }

    private static void append(InboxService service, String source, int priority, String text) {
        SourceView view = SourceView.of(source, source);
        service.appendOrMerge(AppendItem.of(view,
                new InboxEntry(1L, Instant.now(), "某人", text, List.of()), priority,
                Instant.now()));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aLowPriorityItemWaitsForTheBatchDelay() {
        InboxService service = service();
        append(service, "qq_group/1", 200, "群里闲聊");

        assertThat(service.awaitPush(service.pushRevision(), Duration.ofMillis(120)))
                .as("批次还没到点，不能推")
                .isNull();
        InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        assertThat(pushed).isNotNull();
        assertThat(pushed.items()).hasSize(1);
        assertThat(pushed.revision()).isPositive();
    }

    @Test
    void oneImmediateItemFlushesTheWholePendingBatch() {
        InboxService service = service();
        append(service, "qq_group/1", 200, "群里闲聊");
        append(service, "qq_private/2", 350, "私聊急事");

        InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        assertThat(pushed).isNotNull();
        assertThat(pushed.items())
                .as("一条立即项要让所有 pending 一起走，而不是只推它自己")
                .extracting(PanelItem::source)
                .containsExactlyInAnyOrder("qq_group/1", "qq_private/2");
    }

    @Test
    void aLaterArrivalDoesNotRestartTheBatchClock() {
        InboxService service = service();
        append(service, "qq_group/1", 200, "第一条");
        try {
            Thread.sleep(250L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        append(service, "qq_group/2", 200, "第二条");

        long before = System.nanoTime();
        InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - before).toMillis();

        assertThat(pushed).isNotNull();
        assertThat(pushed.items()).hasSize(2);
        assertThat(elapsedMillis)
                .as("以最早一条为准：第二条不能把 400ms 的批次重新计时")
                .isLessThan(350L);
    }

    @Test
    void anAlreadyPushedItemIsNotPushedAgain() {
        InboxService service = service();
        append(service, "qq_group/1", 200, "群里闲聊");
        assertThat(service.awaitPush(service.pushRevision(), Duration.ofSeconds(2))).isNotNull();

        assertThat(service.awaitPush(service.pushRevision(), Duration.ofMillis(200)))
                .as("没人 ack 时也不能每一跳都重复唤醒")
                .isNull();
    }

    @Test
    void aPushRoundResetsTheClockForWhatComesNext() {
        InboxService service = service();
        append(service, "qq_group/1", 200, "第一批");
        InboxPanel first = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        assertThat(first).isNotNull();

        append(service, "qq_group/2", 200, "第二批");
        assertThat(service.awaitPush(service.pushRevision(), Duration.ofMillis(150)))
                .as("上一轮推过之后要重新计时，不能拿上一轮的锚点立刻再推")
                .isNull();
        assertThat(service.awaitPush(service.pushRevision(), Duration.ofSeconds(2))).isNotNull();
    }

    @Test
    void aSameSourceAppendResetsItsOwnDeadline() {
        InboxService service = service();
        append(service, "qq_group/1", 200, "第一条");
        sleep(250L);
        long before = System.nanoTime();
        append(service, "qq_group/1", 200, "第二条");

        InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - before).toMillis();

        assertThat(pushed).isNotNull();
        assertThat(elapsedMillis)
                .as("同源新消息要把该条目的等待重置，而不是沿用第一条的锚点")
                .isGreaterThanOrEqualTo(300L);
    }

    @Test
    void lowPriorityItemsStillPushAfterTheNormalDelay() {
        // The old pushMinPriority hard mute is gone: an item that survived dropBelow must wake
        // after the normal delay even when its score is below the immediate threshold.
        DeliveryPolicy policy = new DeliveryPolicy(IMMEDIATE_THRESHOLD, IMPORTANT, NORMAL);
        SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("filter.db"));
        InboxService service = new InboxService(store, policy, "bma3",
                () -> new InboxRuntime(6, 6, true, IMMEDIATE_THRESHOLD, 30, 5000));
        append(service, "qq_group/1", 200, "低优先也要推");

        assertThat(service.awaitPush(service.pushRevision(), Duration.ofMillis(150)))
                .as("还没到 normalDelay，不能提前推")
                .isNull();
        InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        assertThat(pushed).as("normalDelay 到点后必须推，而不是永远静音").isNotNull();
        assertThat(service.panel().items()).hasSize(1);
    }

    @Test
    void itemsBeyondThePanelWindowAreDeliveredInTheNextRoundInsteadOfVanishing() {
        DeliveryPolicy policy = new DeliveryPolicy(IMMEDIATE_THRESHOLD, IMPORTANT, NORMAL);
        SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("overflow.db"));
        // 8 sources, window of 6: the last two must NOT be marked delivered.
        InboxService service = new InboxService(store, policy, "bma3",
                () -> new InboxRuntime(6, 6, true, -1000, 30, 5000));
        for (int index = 0; index < 8; index++) {
            append(service, "qq_group/" + index, 200, "低优先 " + index);
        }

        InboxPanel first = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        assertThat(first).isNotNull();
        assertThat(first.items()).as("面板窗口只有 6 条").hasSize(6);
        assertThat(first.totalItems()).isEqualTo(8);

        // 客户端按它看到的 revision 确认；窗口外那两条不能被吞掉。
        service.ackPanel(first.revision());
        List<InboxItem> left = service.items();
        assertThat(left).as("没显示过的条目必须还在").hasSize(2);
        assertThat(service.panel().items()).hasSize(2);

        InboxPanel second = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        assertThat(second).as("剩下两条必须马上补推，而不是永远消失").isNotNull();
        assertThat(second.items()).hasSize(2);
    }

    @Test
    void ackingInjectedItemsSurvivesUnrelatedTraffic() {
        // 插件唤醒时：注入面板 → ack(面板 revision + itemIds)。群里活跃时，注入到 ack 之间
        // 会有新消息让 panelRevision 前进。按 itemId 精确 ack 不该因此被拒——它明确指名了
        // 自己看过哪几条，只有"这几条本身在我看到之后又变了"才算冲突。
        DeliveryPolicy policy = new DeliveryPolicy(IMMEDIATE_THRESHOLD, IMPORTANT, NORMAL);
        SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("ack-race.db"));
        InboxService service = new InboxService(store, policy, "bma3",
                () -> new InboxRuntime(6, 6, true, -1000, 30, 5000));
        append(service, "qq_private/1", 300, "旧消息 A");
        InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        assertThat(pushed).isNotNull();
        long seen = pushed.revision();
        String itemId = pushed.items().getFirst().id();

        // 注入之后、ack 之前又来一条
        append(service, "qq_group/2", 300, "新消息 B");

        service.ackItems(Set.of(itemId), seen);   // 修复前：REVISION_CONFLICT
        assertThat(service.items()).extracting(InboxItem::source)
                .containsExactly("qq_group/2");
    }

    @Test
    void aPushedItemIsFrozenAndTheNextMessageOpensASecondItem() {
        // 1B：推送后不再续接；同源新消息另开一条，第二条带「消息更新活跃」标记。
        DeliveryPolicy policy = new DeliveryPolicy(IMMEDIATE_THRESHOLD, IMPORTANT, NORMAL);
        SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("frozen.db"));
        InboxService service = new InboxService(store, policy, "bma3",
                () -> new InboxRuntime(6, 6, true, -1000, 30, 5000));
        append(service, "qq_private/1", 300, "第一条");
        InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        long seen = pushed.revision();
        String firstId = pushed.items().getFirst().id();
        assertThat(service.item(firstId).frozen()).as("推送后先冻结").isTrue();

        service.appendOrMerge(AppendItem.of(
                SourceView.of("qq_private/1", "QQ私聊 1"),
                new InboxEntry(2, Instant.now(), "u", "第二条", List.of()), 300, Instant.now()));

        assertThat(service.items()).as("同源允许两条").hasSize(2);
        InboxItem first = service.item(firstId);
        assertThat(first.frozen()).isTrue();
        InboxItem second = service.items().stream()
                .filter(item -> !item.id().equals(firstId)).findFirst().orElseThrow();
        assertThat(second.continued()).as("第二条带「消息更新活跃」").isTrue();
        assertThat(second.frozen()).isFalse();

        // ack 第一条时同源已经有第二条：这是设计允许的，不应该 409。
        service.ackItems(Set.of(firstId), seen);
        assertThat(service.item(firstId).status()).isEqualTo(ItemStatus.ACKED);
        assertThat(service.items()).hasSize(1);
    }

    @Test
    void ackingAnAlreadyAckedItemIsAnIdempotentNoOp() {
        InboxService service = service();
        append(service, "qq_group/1", 300, "一条");
        InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        String id = pushed.items().getFirst().id();
        service.ackItems(Set.of(id), pushed.revision());
        InboxAck again = service.ackItems(Set.of(id), pushed.revision());
        assertThat(again.remaining()).isZero();
        assertThat(service.items()).isEmpty();
    }

    @Test
    void aRestartDoesNotReplayFrozenItems() {
        DeliveryPolicy policy = new DeliveryPolicy(IMMEDIATE_THRESHOLD, IMPORTANT, NORMAL);
        SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("restart.db"));
        InboxService first = new InboxService(store, policy, "bma3",
                () -> new InboxRuntime(6, 6, true, -1000, 30, 5000));
        append(first, "qq_group/1", 300, "重启前已推送");
        InboxPanel pushed = first.awaitPush(first.pushRevision(), Duration.ofSeconds(2));
        long pushRevision = first.pushRevision();
        assertThat(pushed).isNotNull();
        first.close();

        InboxService restarted = new InboxService(store, policy, "bma3",
                () -> new InboxRuntime(6, 6, true, -1000, 30, 5000));
        assertThat(restarted.pushRevision()).isEqualTo(pushRevision);
        assertThat(restarted.awaitPush(restarted.pushRevision(), Duration.ofMillis(250)))
                .as("已冻结未确认条目重启后不自动重推")
                .isNull();
        assertThat(restarted.panel().items()).hasSize(1);
    }

    @Test
    void dismissClearsLowPriorityItemsAndProtectsImportantOnes() {
        InboxService service = service();
        append(service, "qq_group/1", 100, "低优先");
        append(service, "qq_private/2", 350, "重要");
        DismissResult result = service.dismiss(new DismissRequest("filter", Set.of(), "",
                1000, null, null, 100, false, false, "test"));
        assertThat(result.dismissed()).isEqualTo(1);
        assertThat(result.skippedImportant()).isEqualTo(1);
        assertThat(service.items()).extracting(InboxItem::source)
                .containsExactly("qq_private/2");
    }

    @Test
    void dismissDryRunDoesNotChangeState() {
        InboxService service = service();
        append(service, "qq_group/1", 100, "低优先");
        DismissResult result = service.dismiss(new DismissRequest("visible", Set.of(), "",
                null, null, null, 100, true, false, "preview"));
        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.dismissed()).isZero();
        assertThat(service.items()).hasSize(1);
    }

    @Test
    void panelTruncatesPerItemTextToTheConfiguredBudget() {
        DeliveryPolicy policy = new DeliveryPolicy(IMMEDIATE_THRESHOLD, IMPORTANT, NORMAL);
        SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("budget.db"));
        InboxService service = new InboxService(store, policy, "bma3",
                () -> new InboxRuntime(6, 6, true, -1000, 30, 5000, 100, 60,
                        Integer.MIN_VALUE, 1800, 200));
        append(service, "qq_group/1", 300, "长".repeat(800));
        PanelItem item = service.panel().items().getFirst();
        assertThat(item.truncatedChars()).isPositive();
        assertThat(item.entries().getFirst().render().length())
                .as("单条目注入文本受 maxCharsPerItem 约束")
                .isLessThanOrEqualTo(160);
    }

    @Test
    void sameSourceDuplicatesAreAllowedAndNeverConsolidated() {
        DeliveryPolicy policy = new DeliveryPolicy(IMMEDIATE_THRESHOLD, IMPORTANT, NORMAL);
        SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("duplicates.db"));
        for (int round = 1; round <= 3; round++) {
            InboxService service = new InboxService(store, policy, "bma3",
                    () -> new InboxRuntime(6, 6, true, -1000, 30, 5000));
            service.append(AppendItem.of(SourceView.of("qq_private/1", "QQ私聊 1"),
                    new InboxEntry(round, Instant.now().plusSeconds(round), "u",
                            "第 " + round + " 条", List.of()), 300, Instant.now()));
            service.close();
        }
        InboxService service = new InboxService(store, policy, "bma3",
                () -> new InboxRuntime(6, 6, true, -1000, 30, 5000));
        assertThat(service.items()).as("同源三条未确认条目合法保留").hasSize(3);
    }

    @Test
    void ackingRemovesTheItemFromThePanel() {
        InboxService service = service();
        append(service, "qq_group/1", 200, "群里闲聊");
        InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        assertThat(pushed).isNotNull();

        service.ackPanel(pushed.revision());
        assertThat(service.panel().items()).isEmpty();
    }
}
