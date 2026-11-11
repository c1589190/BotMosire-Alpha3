package io.mosire.bma3.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.core.source.SourceView;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    @Test
    void aLowPriorityItemWaitsForTheBatchDelay() {
        InboxService service = service();
        append(service, "qq_group/1", 200, "群里闲聊");

        assertThat(service.awaitPush(Duration.ofMillis(120)))
                .as("批次还没到点，不能推")
                .isNull();
        InboxPanel pushed = service.awaitPush(Duration.ofSeconds(2));
        assertThat(pushed).isNotNull();
        assertThat(pushed.items()).hasSize(1);
        assertThat(pushed.revision()).isPositive();
    }

    @Test
    void oneImmediateItemFlushesTheWholePendingBatch() {
        InboxService service = service();
        append(service, "qq_group/1", 200, "群里闲聊");
        append(service, "qq_private/2", 350, "私聊急事");

        InboxPanel pushed = service.awaitPush(Duration.ofSeconds(2));
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
        InboxPanel pushed = service.awaitPush(Duration.ofSeconds(2));
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
        assertThat(service.awaitPush(Duration.ofSeconds(2))).isNotNull();

        assertThat(service.awaitPush(Duration.ofMillis(200)))
                .as("没人 ack 时也不能每一跳都重复唤醒")
                .isNull();
    }

    @Test
    void aPushRoundResetsTheClockForWhatComesNext() {
        InboxService service = service();
        append(service, "qq_group/1", 200, "第一批");
        InboxPanel first = service.awaitPush(Duration.ofSeconds(2));
        assertThat(first).isNotNull();

        append(service, "qq_group/2", 200, "第二批");
        assertThat(service.awaitPush(Duration.ofMillis(150)))
                .as("上一轮推过之后要重新计时，不能拿上一轮的锚点立刻再推")
                .isNull();
        assertThat(service.awaitPush(Duration.ofSeconds(2))).isNotNull();
    }

    @Test
    void itemsBelowThePushFilterStayVisibleButNeverWake() {
        DeliveryPolicy policy = new DeliveryPolicy(IMMEDIATE_THRESHOLD, IMPORTANT, NORMAL);
        SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("filter.db"));
        InboxService service = new InboxService(store, policy, "bma3",
                () -> new InboxRuntime(6, 6, true, IMMEDIATE_THRESHOLD, 30, 5000));
        append(service, "qq_group/1", 200, "太低，不推");

        assertThat(service.awaitPush(Duration.ofMillis(500))).isNull();
        assertThat(service.panel().items()).as("工具侧仍然看得到").hasSize(1);
    }

    @Test
    void ackingRemovesTheItemFromThePanel() {
        InboxService service = service();
        append(service, "qq_group/1", 200, "群里闲聊");
        InboxPanel pushed = service.awaitPush(Duration.ofSeconds(2));
        assertThat(pushed).isNotNull();

        service.ackPanel(pushed.revision());
        assertThat(service.panel().items()).isEmpty();
    }
}
