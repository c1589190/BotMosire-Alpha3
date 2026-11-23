package io.github.c1589190.bma3.app.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.core.source.SourceView;
import io.github.c1589190.bma3.inbox.core.AppendItem;
import io.github.c1589190.bma3.inbox.core.DeliveryPolicy;
import io.github.c1589190.bma3.inbox.core.InboxItem;
import io.github.c1589190.bma3.inbox.core.InboxService;
import io.github.c1589190.bma3.inbox.core.SqliteInboxStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Acceptance A2–A6, A8, A9 of the debug surface (spec 2026-11-15 §2.3). */
class DebugServiceTest {

    @TempDir
    Path tempDir;

    private static AppConfig.Debug debug(int maxPending, int questionMaxBytes) {
        return new AppConfig.Debug(true, maxPending, 120, questionMaxBytes, 16384, 200, 350);
    }

    private DebugService service(Path dir, AppConfig.Debug limits,
                                 java.util.function.Function<DebugRequest, Boolean> sink) {
        return new DebugService(new DebugStore(dir.resolve("debug")), () -> limits, sink);
    }

    @Test
    void askInjectsExactlyOneInboxItemAtTheConfiguredPriority() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("inbox.db"))) {
            InboxService inbox = new InboxService(store, DeliveryPolicy.defaults(), "bma3");
            DebugService service = service(tempDir, debug(32, 4096), request -> {
                inbox.append(new AppendItem(SourceView.of(request.injectedSource(), "调试提问"),
                        null, DebugService.inboxSummary(request), 350, Instant.now()));
                return true;
            });

            DebugRequest asked = service.ask("远端为什么没推送？", null, false, "127.0.0.1");

            List<InboxItem> items = inbox.items();
            assertThat(items).hasSize(1);
            assertThat(items.getFirst().source()).isEqualTo("bma3_debug/" + asked.id());
            assertThat(items.getFirst().priority()).isGreaterThanOrEqualTo(300);
            assertThat(items.getFirst().summary())
                    .contains(asked.id())
                    .contains("远端为什么没推送？")
                    .contains("bma3_debug_reply");
        }
    }

    @Test
    void answerCompletesTheRequestAndIsReadBack() {
        DebugService service = service(tempDir, debug(32, 4096), request -> true);
        DebugRequest asked = service.ask("现状如何", null, false, "127.0.0.1");

        DebugRequest answered = service.answer(asked.id(), "探针都 UP，面板 12 条", "agent");

        assertThat(answered.status()).isEqualTo(DebugRequest.Status.ANSWERED);
        assertThat(service.get(asked.id()).answer()).isEqualTo("探针都 UP，面板 12 条");
        assertThat(answered.answeredAt()).isNotNull();
    }

    @Test
    void expiredRequestRejectsALateAnswer() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-15T00:00:00Z"));
        DebugService service = new DebugService(new DebugStore(tempDir),
                () -> debug(32, 4096), clock, request -> true);
        DebugRequest asked = service.ask("还在吗", 5, false, "127.0.0.1");

        clock.advance(Duration.ofSeconds(6));
        service.sweep();

        assertThat(service.get(asked.id()).status()).isEqualTo(DebugRequest.Status.EXPIRED);
        assertThatThrownBy(() -> service.answer(asked.id(), "迟到的回答", "agent"))
                .isInstanceOf(DebugException.class)
                .hasMessageContaining("过期");
    }

    @Test
    void maxPendingCapsUnansweredQuestionsAndZeroMeansUnlimited() {
        DebugService capped = service(tempDir.resolve("capped"), debug(1, 4096), request -> true);
        capped.ask("第一个", null, false, "127.0.0.1");
        assertThatThrownBy(() -> capped.ask("第二个", null, false, "127.0.0.1"))
                .isInstanceOf(DebugException.class)
                .hasMessageContaining("maxPending");

        DebugService unlimited = service(tempDir.resolve("unlimited"), debug(0, 4096),
                request -> true);
        for (int i = 0; i < 5; i++) {
            unlimited.ask("第 " + i + " 个", null, false, "127.0.0.1");
        }
        assertThat(unlimited.pendingCount()).isEqualTo(5);
    }

    @Test
    void restartKeepsPendingQuestionsWithoutReinjectingThem() {
        AtomicInteger injections = new AtomicInteger();
        DebugService first = service(tempDir, debug(32, 4096), request -> {
            injections.incrementAndGet();
            return true;
        });
        DebugRequest asked = first.ask("重启前的问题", null, false, "127.0.0.1");

        DebugService restarted = service(tempDir, debug(32, 4096), request -> {
            injections.incrementAndGet();
            return true;
        });

        assertThat(restarted.get(asked.id()).status()).isEqualTo(DebugRequest.Status.PENDING);
        assertThat(injections).hasValue(1);
    }

    @Test
    void auditLogNeverContainsQuestionOrAnswerBodies() throws Exception {
        DebugService service = service(tempDir, debug(32, 4096), request -> true);
        DebugRequest asked = service.ask("SECRET-QUESTION-MARKER", null, false, "127.0.0.1");
        service.answer(asked.id(), "SECRET-ANSWER-MARKER", "agent");

        String audit = Files.readString(tempDir.resolve("debug").resolve("audit.jsonl"));
        assertThat(audit).contains(asked.id()).contains("asked").contains("answered");
        assertThat(audit).doesNotContain("SECRET-QUESTION-MARKER").doesNotContain("SECRET-ANSWER-MARKER");
    }

    @Test
    void oversizedQuestionIsRejectedBeforeAnyInjection() {
        AtomicInteger injections = new AtomicInteger();
        DebugService service = service(tempDir, debug(32, 256), request -> {
            injections.incrementAndGet();
            return true;
        });

        assertThatThrownBy(() -> service.ask("x".repeat(300), null, false, "127.0.0.1"))
                .isInstanceOf(DebugException.class)
                .hasMessageContaining("超过");
        assertThat(injections).hasValue(0);
    }

    @Test
    void failedInjectionIsReportedInsteadOfPretendingTheQuestionWasAsked() {
        DebugService service = service(tempDir, debug(32, 4096), request -> false);

        assertThatThrownBy(() -> service.ask("应该失败", null, false, "127.0.0.1"))
                .isInstanceOf(DebugException.class)
                .hasMessageContaining("Inbox");
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
