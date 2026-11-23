package io.github.c1589190.bma3.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.c1589190.bma3.core.source.SourceView;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboxSourceWaitTest {

    private static final int IMPORTANT = 300;
    private static final Duration IMPORTANT_DELAY = Duration.ofMillis(40);
    private static final Duration NORMAL_DELAY = Duration.ofMillis(400);

    @TempDir
    Path tempDir;
    private SqliteInboxStore store;

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    private InboxService service(String db, InboxWaitPolicy waitPolicy) {
        store = new SqliteInboxStore(tempDir.resolve(db));
        DeliveryPolicy delivery = new DeliveryPolicy(IMPORTANT, IMPORTANT_DELAY, NORMAL_DELAY);
        InboxRuntime runtime = new InboxRuntime(6, 6, true, -1000, 30, 5000, 1200, 60,
                Integer.MIN_VALUE, 1800, 200, "full", 200, 8, waitPolicy);
        return new InboxService(store, delivery, "bma3", () -> runtime);
    }

    private static void append(InboxService service, String source, int priority, String text) {
        SourceView view = SourceView.of(source, source);
        service.appendOrMerge(AppendItem.of(view,
                new InboxEntry(1L, Instant.now(), "某人", text, List.of()), priority,
                Instant.now()));
    }

    @Test
    void sameSourceMessagesWaitForQuietAndMerge() {
        InboxService service = service("quiet.db", new InboxWaitPolicy(true, 0, 20,
                List.of(new InboxWaitPolicy.Rule("qq_private", 1, 3))));
        append(service, "qq_private/bot-1/2", IMPORTANT, "第一句");
        sleep(80);
        append(service, "qq_private/bot-1/2", IMPORTANT, "第二句");

        assertThat(service.status().waitingForQuiet()).isEqualTo(1);
        long before = System.nanoTime();
        InboxPanel panel = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - before).toMillis();

        assertThat(panel).isNotNull();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(800L);
        assertThat(panel.items()).hasSize(1);
        assertThat(panel.items().getFirst().entries()).hasSize(2);
    }

    @Test
    void quietItemIsNotFlushedByAnotherSource() {
        InboxService service = service("held.db", new InboxWaitPolicy(true, 0, 20,
                List.of(new InboxWaitPolicy.Rule("qq_private", 2, 3))));
        append(service, "qq_private/bot-1/2", IMPORTANT, "还在输入");
        append(service, "bma3_task/run-1", IMPORTANT, "独立任务");

        InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        assertThat(pushed).isNotNull();
        assertThat(pushed.items()).hasSize(1);
        PanelItem taskPushed = pushed.items().getFirst();
        assertThat(taskPushed.source()).startsWith("bma3_task");
        assertThat(taskPushed.frozenRevision()).isPositive();

        PanelItem privateItem = service.panel().items().stream()
                .filter(item -> item.source().startsWith("qq_private")).findFirst().orElseThrow();
        assertThat(privateItem.frozenRevision()).as("quiet-held item must stay pending").isZero();
        assertThat(privateItem.waitingForQuiet()).as("panel must mark the quiet hold").isTrue();
        assertThat(service.status().waitingForQuiet()).isEqualTo(1);
    }

    @Test
    void hardWaitReleasesAContinuouslyActiveSource() {
        InboxService service = service("hard.db", new InboxWaitPolicy(true, 0, 20,
                List.of(new InboxWaitPolicy.Rule("qq_private", 1, 1))));
        append(service, "qq_private/bot-1/2", IMPORTANT, "一");
        sleep(300);
        append(service, "qq_private/bot-1/2", IMPORTANT, "二");
        sleep(300);
        append(service, "qq_private/bot-1/2", IMPORTANT, "三");

        InboxPanel panel = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        assertThat(panel).isNotNull();
        assertThat(panel.items()).hasSize(1);
        assertThat(panel.items().getFirst().entries()).hasSize(3);
        assertThat(panel.items().getFirst().frozenRevision()).isPositive();
    }

    @Test
    void quietZeroPreservesExistingBatchBehavior() {
        InboxService service = service("zero.db", new InboxWaitPolicy(true, 0, 20,
                List.of(new InboxWaitPolicy.Rule("qq_private", 0, 20))));
        append(service, "qq_private/bot-1/2", IMPORTANT, "私聊");
        append(service, "bma3_task/run-1", IMPORTANT, "任务");

        InboxPanel panel = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
        assertThat(panel).isNotNull();
        assertThat(panel.items()).hasSize(2);
        assertThat(panel.items()).allMatch(item -> item.frozenRevision() > 0);
    }

    @Test
    void waitPolicyViewExposesEffectiveRule() {
        InboxService service = service("view.db", new InboxWaitPolicy(true, 0, 20,
                List.of(new InboxWaitPolicy.Rule("qq_private", 2, 3))));
        append(service, "qq_private/bot-1/2", IMPORTANT, "等待");

        assertThat(service.waitPolicyView())
                .containsEntry("enabled", true)
                .containsEntry("defaultQuietSeconds", 0)
                .containsEntry("waitingForQuiet", 1);
        assertThat(service.waitPolicyView().get("rules").toString())
                .contains("qq_private", "2", "3");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
